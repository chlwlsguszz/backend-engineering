package com.marketengine.backend.product.infrastructure.search;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 3: stream products from PostgreSQL and bulk-index into Elasticsearch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
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

    public ReindexResult reindex(boolean recreateIndex) {
        Instant startedAt = Instant.now();

        if (recreateIndex) {
            recreateIndex();
        }

        long totalRows = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        log.info("Starting product reindex: totalRows={}, batchSize={}", totalRows, batchSize);

        IndexCoordinates index = IndexCoordinates.of(ProductDocument.INDEX_NAME);
        long lastId = 0L;
        long indexed = 0L;
        int batchNumber = 0;

        while (true) {
            List<ProductDocument> batch = jdbcTemplate.query(BATCH_SQL, ROW_MAPPER, lastId, batchSize);
            if (batch.isEmpty()) {
                break;
            }

            elasticsearchOperations.save(batch, index);
            batchNumber++;
            indexed += batch.size();
            lastId = batch.get(batch.size() - 1).getId();

            if (batchNumber % logEveryBatches == 0 || indexed >= totalRows) {
                log.info("Reindex progress: indexed={}/{} ({}%)", indexed, totalRows, percent(indexed, totalRows));
            }
        }

        elasticsearchOperations.indexOps(ProductDocument.class).refresh();

        Duration duration = Duration.between(startedAt, Instant.now());
        log.info("Product reindex finished: indexed={}, duration={}s", indexed, duration.toSeconds());
        return new ReindexResult(indexed, duration);
    }

    private void recreateIndex() {
        IndexOperations indexOperations = elasticsearchOperations.indexOps(ProductDocument.class);
        if (indexOperations.exists()) {
            indexOperations.delete();
            log.info("Deleted Elasticsearch index: {}", ProductDocument.INDEX_NAME);
        }
        indexOperations.createWithMapping();
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
}
