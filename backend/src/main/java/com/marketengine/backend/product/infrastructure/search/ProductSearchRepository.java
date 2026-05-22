package com.marketengine.backend.product.infrastructure.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * Step 2: repository for index CRUD; search queries are added in later steps.
 */
public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, Long> {
}
