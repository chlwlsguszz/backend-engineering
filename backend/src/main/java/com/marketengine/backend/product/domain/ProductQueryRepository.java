package com.marketengine.backend.product.domain;

import org.springframework.data.domain.Slice;
import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;

public interface ProductQueryRepository {

    Slice<ProductSummaryResponse> search(ProductListQuery query);
}
