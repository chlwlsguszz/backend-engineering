package com.marketengine.backend.product.domain;

import org.springframework.data.domain.Slice;

public interface ProductQueryRepository {

    Slice<Product> search(ProductListQuery query);
}
