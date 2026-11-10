package com.marketengine.backend.product.infrastructure.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.stereotype.Component;

import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox.Entry;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "marketengine.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ProductOutboxPublisher {

    // A delete keeps its revision so even a late delivery of an old upsert cannot restore it.
    private static final String APPLY_IF_NEWER = """
            if (ctx._source.outboxRevision != null && ctx._source.outboxRevision >= params.doc.outboxRevision) {
                ctx.op = 'noop';
            } else {
                ctx._source.clear();
                ctx._source.putAll(params.doc);
            }
            """;

    private final ElasticsearchClient client;
    private final ElasticsearchConverter converter;

    public List<Entry> publish(List<Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        BulkRequest request = BulkRequest.of(b -> b.index(ProductDocument.INDEX_NAME)
                .operations(entries.stream().map(this::operation).toList()));
        try {
            return successfulEntries(entries, client.bulk(request).items());
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to publish the product outbox batch", failure);
        }
    }

    private BulkOperation operation(Entry entry) {
        Map<String, Object> source;
        if (entry.document() == null) {
            source = Map.of("id", entry.productId(), "outboxRevision", entry.revision(), "outboxDeleted", true);
        } else {
            source = new HashMap<>(converter.mapObject(entry.document()));
            source.put("outboxRevision", entry.revision());
        }
        return BulkOperation.of(b -> b.update(u -> u.id(Long.toString(entry.productId())).retryOnConflict(3)
                .action(a -> a.scriptedUpsert(true).upsert(Map.of())
                        .script(s -> s.source(APPLY_IF_NEWER).params("doc", JsonData.of(source))))));
    }

    private static List<Entry> successfulEntries(List<Entry> entries, List<BulkResponseItem> items) {
        if (entries.size() != items.size()) {
            throw new IllegalStateException("Incomplete response to the product outbox bulk request");
        }
        List<Entry> successful = new ArrayList<>(entries.size());
        BulkResponseItem firstFailure = null;
        for (int i = 0; i < items.size(); i++) {
            BulkResponseItem item = items.get(i);
            if (item.error() == null && item.status() >= 200 && item.status() < 300) {
                successful.add(entries.get(i));
            } else if (firstFailure == null) {
                firstFailure = item;
            }
        }
        if (firstFailure != null) {
            log.warn("Product outbox bulk failed for {}/{} entries. First failure: productId={}, status={}, reason={}",
                    entries.size() - successful.size(), entries.size(), firstFailure.id(), firstFailure.status(),
                    firstFailure.error() == null ? "unknown" : firstFailure.error().reason());
        }
        return successful;
    }
}
