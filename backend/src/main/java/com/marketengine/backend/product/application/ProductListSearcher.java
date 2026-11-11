package com.marketengine.backend.product.application;

import org.springframework.data.domain.Slice;

import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.domain.ProductListQuery;

/**
 * Product list/search port (step 5: default implementation uses Elasticsearch).
 */
public interface ProductListSearcher {

    Slice<ProductSummaryResponse> search(ProductListQuery query);

    /** Cursor positions are not interchangeable between token-search ES and substring-search PostgreSQL. */
    String cursorScope();
}
