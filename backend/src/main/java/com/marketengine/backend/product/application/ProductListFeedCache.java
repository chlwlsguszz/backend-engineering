package com.marketengine.backend.product.application;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import com.marketengine.backend.common.config.RedisCacheConfig;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductListFeedCache {

    private final ProductListSearcher productListSearcher;

    @Cacheable(
            cacheNames = RedisCacheConfig.PRODUCT_LIST_FEED,
            key = "#feedKey.value()"
    )
    public ProductPageResponse get(ProductListFeedKey feedKey) {
        ProductListFeedQuery query = feedKey.query();
        Pageable pageable = PageRequest.of(query.page(), query.size());
        Slice<ProductSummaryResponse> pageResult = productListSearcher.search(
                query.keyword(),
                query.category(),
                query.brand(),
                query.gender(),
                query.color(),
                query.minPrice(),
                query.maxPrice(),
                query.sortBy(),
                pageable
        );
        return ProductPageResponse.from(pageResult);
    }
}
