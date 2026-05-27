package com.marketengine.backend.product.infrastructure.search;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 3: run with {@code --spring.profiles.active=reindex} (see scripts/elasticsearch/run-reindex-products.ps1).
 * Optional flag: {@code --recreate-index} deletes and recreates the products index before bulk load.
 */
@Slf4j
@Profile("reindex")
@Component
@RequiredArgsConstructor
public class ProductReindexApplicationRunner implements ApplicationRunner {

    private final ProductBulkReindexer bulkReindexer;
    private final ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) {
        boolean recreateIndex = args.containsOption("recreate-index");
        try {
            ProductBulkReindexer.ReindexResult result = bulkReindexer.reindex(recreateIndex);
            log.info(
                    "Reindex job complete: indexed={}, duration={}s",
                    result.indexedCount(),
                    result.duration().toSeconds()
            );
        } catch (RuntimeException exception) {
            log.error("Reindex job failed", exception);
            exit(1);
        }
        exit(0);
    }

    private void exit(int code) {
        int exitCode = SpringApplication.exit(applicationContext, () -> code);
        System.exit(exitCode);
    }
}
