package com.marketengine.backend.product.application;

import com.marketengine.backend.product.domain.ProductCategory;

/**
 * Search parameters for a cacheable product list feed request.
 */
public record ProductListFeedQuery(
        String keyword,
        ProductCategory category,
        String brand,
        String gender,
        String color,
        Integer minPrice,
        Integer maxPrice,
        String sortBy,
        int page,
        int size
) {
}
