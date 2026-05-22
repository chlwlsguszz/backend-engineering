package com.marketengine.backend.product.infrastructure.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 2: create the {@value ProductDocument#INDEX_NAME} index and mapping if missing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "marketengine.elasticsearch.index.ensure-on-startup", havingValue = "true", matchIfMissing = true)
public class ProductIndexInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        IndexOperations indexOperations = elasticsearchOperations.indexOps(ProductDocument.class);
        if (indexOperations.exists()) {
            log.info("Elasticsearch index already exists: {}", ProductDocument.INDEX_NAME);
            return;
        }
        indexOperations.createWithMapping();
        log.info("Elasticsearch index created: {}", ProductDocument.INDEX_NAME);
    }
}
