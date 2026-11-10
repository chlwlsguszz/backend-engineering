package com.marketengine.backend.product.application;

import com.marketengine.backend.product.domain.ProductListQuery;

public record ProductListFeedKey(
        String value,
        ProductListQuery query
) {
}
