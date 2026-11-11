package com.marketengine.backend.product.infrastructure.search;

import java.io.IOException;
import java.util.List;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;

/** Index creation and temporary settings belong to the offline indexing lifecycle. */
@Component
@ConditionalOnProperty(name = "marketengine.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ProductIndexManager {
    private final ElasticsearchOperations operations;
    private final ElasticsearchClient client;
    private final boolean sortLatest;
    private final boolean suspendRefresh;

    public ProductIndexManager(ElasticsearchOperations operations, ElasticsearchClient client,
                              @Value("${marketengine.elasticsearch.index.sort-latest:true}") boolean sortLatest,
                              @Value("${marketengine.elasticsearch.reindex.suspend-refresh:true}") boolean suspendRefresh) {
        this.operations = operations;
        this.client = client;
        this.sortLatest = sortLatest;
        this.suspendRefresh = suspendRefresh;
    }

    public void create() {
        var index = operations.indexOps(ProductDocument.class);
        var settings = index.createSettings();
        if (sortLatest) {
            settings.put("index.sort.field", List.of("createdAt", "id"));
            settings.put("index.sort.order", List.of("desc", "desc"));
        }
        if (!index.create(settings, index.createMapping())) {
            throw new IllegalStateException("Index creation was not acknowledged: " + ProductDocument.INDEX_NAME);
        }
    }

    RefreshSuspension suspendRefresh() {
        if (!suspendRefresh) {
            return () -> {};
        }
        Object previous = operations.indexOps(ProductDocument.class)
                .getSettings(false).flatten().get("index.refresh_interval");
        JsonData restore = previous == null ? JsonData.fromJson("null") : JsonData.of(previous.toString());
        try {
            setRefresh(JsonData.of("-1"));
        } catch (RuntimeException failure) {
            // The request may have reached Elasticsearch even if its acknowledgement was lost.
            try {
                restoreRefresh(restore);
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
        return () -> restoreRefresh(restore);
    }

    private void restoreRefresh(JsonData value) {
        // A cancelled reindex must still restore refresh before preserving cancellation for its caller.
        boolean interrupted = Thread.interrupted();
        try {
            setRefresh(value);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void setRefresh(JsonData value) {
        try {
            boolean acknowledged = client.indices().putSettings(request -> request.index(ProductDocument.INDEX_NAME)
                    .settings(settings -> settings.otherSettings("index.refresh_interval", value))).acknowledged();
            if (!acknowledged) {
                throw new IllegalStateException("Refresh interval update was not acknowledged");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not update product index refresh interval", failure);
        }
    }

    interface RefreshSuspension extends AutoCloseable {
        @Override
        void close();
    }
}
