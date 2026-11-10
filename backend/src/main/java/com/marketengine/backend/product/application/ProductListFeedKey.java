package com.marketengine.backend.product.application;

/**
 * Canonical Redis key and the original parameters to execute on a cache miss.
 */
public record ProductListFeedKey(
        String value,
        ProductListFeedQuery query
) {
}
