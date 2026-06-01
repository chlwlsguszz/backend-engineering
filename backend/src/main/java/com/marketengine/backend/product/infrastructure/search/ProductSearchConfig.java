package com.marketengine.backend.product.infrastructure.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

@Configuration
@EnableElasticsearchRepositories(basePackages = "com.marketengine.backend.product.infrastructure.search")
@ConditionalOnProperty(name = "marketengine.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ProductSearchConfig {
}
