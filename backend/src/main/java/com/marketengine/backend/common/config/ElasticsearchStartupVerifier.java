package com.marketengine.backend.common.config;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 1: confirms the app can reach Elasticsearch at startup.
 * Search features are added in later steps.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "marketengine.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "marketengine.elasticsearch.verify-on-startup", havingValue = "true", matchIfMissing = true)
public class ElasticsearchStartupVerifier implements ApplicationListener<ApplicationReadyEvent> {

    private final ElasticsearchClient elasticsearchClient;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            InfoResponse info = elasticsearchClient.info();
            log.info(
                    "Elasticsearch connected: cluster={}, version={}",
                    info.clusterName(),
                    info.version().number()
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Elasticsearch is not reachable at spring.elasticsearch.uris. "
                            + "Start the cluster (e.g. docker compose up -d elasticsearch) "
                            + "or set marketengine.elasticsearch.verify-on-startup=false.",
                    exception
            );
        }
    }
}
