package com.marketengine.backend.common.config;

import java.time.Duration;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.elasticsearch.RestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

/** Keep search and background indexing within one explicitly bounded HTTP pool. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "marketengine.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchHttpConfig {

    @Bean
    RestClientBuilderCustomizer productElasticsearchHttpCustomizer(
            @Value("${marketengine.elasticsearch.http.max-connections-per-route:32}") int perRoute,
            @Value("${marketengine.elasticsearch.http.max-connections:64}") int total,
            @Value("${marketengine.elasticsearch.http.connection-acquire-timeout:1s}") Duration acquireTimeout,
            @Value("${marketengine.elasticsearch.http.connect-timeout:1s}") Duration connectTimeout,
            @Value("${marketengine.elasticsearch.http.read-timeout:30s}") Duration readTimeout) {
        Assert.isTrue(perRoute > 0 && total >= perRoute,
                "Elasticsearch total connections must be at least the positive per-route limit");
        int acquireMs = milliseconds(acquireTimeout);
        int connectMs = milliseconds(connectTimeout);
        int readMs = milliseconds(readTimeout);
        return new RestClientBuilderCustomizer() {
            @Override
            public void customize(RestClientBuilder builder) {
                // Customize Boot's existing builders below, preserving authentication and SSL callbacks.
            }

            @Override
            public void customize(HttpAsyncClientBuilder builder) {
                builder.setMaxConnPerRoute(perRoute).setMaxConnTotal(total);
            }

            @Override
            public void customize(RequestConfig.Builder builder) {
                builder.setConnectionRequestTimeout(acquireMs)
                        .setConnectTimeout(connectMs).setSocketTimeout(readMs);
            }
        };
    }

    private static int milliseconds(Duration value) {
        Assert.isTrue(value.toMillis() > 0 && value.toMillis() <= Integer.MAX_VALUE,
                "Elasticsearch HTTP timeouts must be positive milliseconds within the integer range");
        return Math.toIntExact(value.toMillis());
    }
}
