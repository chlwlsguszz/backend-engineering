package com.marketengine.backend.product.application;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.domain.ProductCategory;

/**
 * Product list/search port (step 5: default implementation uses Elasticsearch).
 */
public interface ProductListSearcher {

    Slice<ProductSummaryResponse> search(
            String keyword,
            ProductCategory category,
            String brand,
            String gender,
            String color,
            Integer minPrice,
            Integer maxPrice,
            String sortBy,
            Pageable pageable
    );
}
