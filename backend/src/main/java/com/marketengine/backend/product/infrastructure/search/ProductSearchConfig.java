package com.marketengine.backend.product.infrastructure.search;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

@Configuration
@EnableElasticsearchRepositories(basePackages = "com.marketengine.backend.product.infrastructure.search")
public class ProductSearchConfig {
}
