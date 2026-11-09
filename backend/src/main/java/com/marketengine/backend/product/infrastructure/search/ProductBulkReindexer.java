package com.marketengine.backend.product.infrastructure.search;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Offline bulk load from a stable PostgreSQL catalog into the product search index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "marketengine.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ProductBulkReindexer {

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM products";

    private static final String BATCH_SQL = """
            SELECT id, name, price_amount, stock_quantity, category, brand, color, gender,
                   popularity_score, created_at
            FROM products
            WHERE id > ?
            ORDER BY id
            LIMIT ?
            """;

    private static final RowMapper<ProductDocument> ROW_MAPPER = (resultSet, rowNum) -> new ProductDocument(
            resultSet.getLong("id"),
            resultSet.getString("name"),
            resultSet.getBigDecimal("price_amount"),
            resultSet.getInt("stock_quantity"),
            resultSet.getString("category"),
            resultSet.getString("brand"),
            resultSet.getString("color"),
            resultSet.getString("gender"),
            resultSet.getInt("popularity_score"),
            resultSet.getObject("created_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbcTemplate;
    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${marketengine.elasticsearch.reindex.batch-size:5000}")
    private int batchSize;

    @Value("${marketengine.elasticsearch.reindex.log-every-batches:20}")
    private int logEveryBatches;

    @Value("${marketengine.elasticsearch.reindex.workers:1}")
    private int workers;

    public ReindexResult reindex(boolean recreateIndex) {
        if (workers < 0 || workers > 8 || batchSize < 1 || logEveryBatches < 1) {
            throw new IllegalArgumentException(
                    "Reindex requires 0..8 workers and positive batch-size and log-every-batches");
        }
        long startedAt = System.nanoTime();
        long totalRows = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        checkInterrupted();
        if (recreateIndex) {
            recreateIndex();
        }

        log.info("Starting product reindex: totalRows={}, batchSize={}, workers={}", totalRows, batchSize, workers);

        IndexCoordinates index = IndexCoordinates.of(ProductDocument.INDEX_NAME);
        TransferStats stats = transfer(index, totalRows);
        checkInterrupted();
        if (stats.indexed() != totalRows) {
            throw new IllegalStateException("Source count changed during reindex: expected "
                    + totalRows + " products but indexed " + stats.indexed());
        }

        long refreshStart = System.nanoTime();
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
        long refreshNanos = System.nanoTime() - refreshStart;
        long targetDocuments = elasticsearchOperations.count(Query.findAll(), ProductDocument.class, index);
        if (targetDocuments < totalRows || (recreateIndex && targetDocuments != totalRows)) {
            throw new IllegalStateException("Reindex count mismatch: source=" + totalRows + ", target=" + targetDocuments);
        }
        if (targetDocuments > totalRows) {
            log.warn("Existing product index contains extra documents: source={}, target={}", totalRows, targetDocuments);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        log.info("Product reindex finished: indexed={}, targetDocuments={}, workers={}, dbReadMs={}, bulkWorkerMs={}, "
                        + "finalRefreshMs={}, wallMs={}",
                stats.indexed(), targetDocuments, workers, TimeUnit.NANOSECONDS.toMillis(stats.readNanos()),
                TimeUnit.NANOSECONDS.toMillis(stats.bulkNanos()), TimeUnit.NANOSECONDS.toMillis(refreshNanos),
                elapsed.toMillis());
        return new ReindexResult(stats.indexed(), elapsed);
    }

    private TransferStats transfer(IndexCoordinates index, long totalRows) {
        if (workers == 0) {
            return copyBatches(index, totalRows, Runnable::run, 1);
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(workers,
                Thread.ofPlatform().name("product-reindex-", 1).factory())) {
            try {
                // At most one extra batch can queue while all writers are busy.
                return copyBatches(index, totalRows, pool, workers + 1);
            } catch (RuntimeException | Error failure) {
                pool.shutdownNow();
                throw failure;
            }
        }
    }

    private TransferStats copyBatches(IndexCoordinates index, long totalRows, Executor executor, int maxPending) {
        var completions = new ExecutorCompletionService<BatchWrite>(executor);
        long lastReadId = 0;
        long indexed = 0;
        long readNanos = 0;
        long bulkNanos = 0;
        int completedBatches = 0;
        int pending = 0;
        boolean sourceExhausted = false;

        try {
            while (!sourceExhausted || pending > 0) {
                checkInterrupted();
                Future<BatchWrite> finished = completions.poll();
                if (finished == null && (sourceExhausted || pending == maxPending)) {
                    finished = completions.take();
                }
                if (finished != null) {
                    BatchWrite write = finished.get();
                    pending--;
                    indexed += write.size();
                    bulkNanos += write.elapsedNanos();
                    completedBatches++;
                    if (completedBatches % logEveryBatches == 0 || indexed == totalRows) {
                        log.info("Reindex progress: indexed={}/{} ({}%)", indexed, totalRows, percent(indexed, totalRows));
                    }
                    continue;
                }

                long readStart = System.nanoTime();
                List<ProductDocument> batch = jdbcTemplate.query(BATCH_SQL, ROW_MAPPER, lastReadId, batchSize);
                readNanos += System.nanoTime() - readStart;
                checkInterrupted();
                if (batch.isEmpty()) {
                    sourceExhausted = true;
                } else {
                    lastReadId = batch.getLast().getId();
                    completions.submit(() -> writeBatch(index, batch));
                    pending++;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Product reindex interrupted", interrupted);
        } catch (ExecutionException failedWrite) {
            throw new IllegalStateException("Product reindex bulk write failed", failedWrite.getCause());
        }
        return new TransferStats(indexed, readNanos, bulkNanos);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Product reindex interrupted");
        }
    }

    private BatchWrite writeBatch(IndexCoordinates index, List<ProductDocument> batch) {
        long start = System.nanoTime();
        try {
            elasticsearchOperations.save(batch, index);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Failed to index product IDs " + batch.getFirst().getId()
                    + " through " + batch.getLast().getId(), failure);
        }
        return new BatchWrite(batch.size(), System.nanoTime() - start);
    }

    private void recreateIndex() {
        IndexOperations indexOperations = elasticsearchOperations.indexOps(ProductDocument.class);
        if (indexOperations.exists()) {
            indexOperations.delete();
            log.info("Deleted Elasticsearch index: {}", ProductDocument.INDEX_NAME);
        }
        if (!indexOperations.createWithMapping()) {
            throw new IllegalStateException("Index creation was not acknowledged: " + ProductDocument.INDEX_NAME);
        }
        log.info("Created Elasticsearch index: {}", ProductDocument.INDEX_NAME);
    }

    private static int percent(long done, long total) {
        if (total == 0L) {
            return 100;
        }
        return (int) Math.min(100L, (done * 100L) / total);
    }

    public record ReindexResult(long indexedCount, Duration duration) {
    }

    private record BatchWrite(int size, long elapsedNanos) {}

    private record TransferStats(long indexed, long readNanos, long bulkNanos) {}
}
