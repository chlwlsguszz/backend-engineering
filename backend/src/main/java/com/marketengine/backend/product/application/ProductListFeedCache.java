package com.marketengine.backend.product.application;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.marketengine.backend.common.config.RedisCacheConfig;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;

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
        return ProductPageResponse.from(productListSearcher.search(feedKey.query()));
    }
}
