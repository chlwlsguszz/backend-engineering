package com.marketengine.backend.product.application;

/**
 * Fixed Redis cache key for a product list feed plus its search parameters.
 */
public record ProductListFeedKey(
        String value,
        ProductListFeedQuery query
) {
}
