package com.marketengine.backend.product.application;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import com.marketengine.backend.common.config.RedisCacheConfig;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.domain.ProductCategory;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductListFeedCache {

    private final ProductListSearcher productListSearcher;

    @Cacheable(
            cacheNames = RedisCacheConfig.PRODUCT_LIST_FEED,
            key = "#sortBy + ':' + #page + ':' + #size"
                    + " + ':' + (#category == null ? '-' : #category.name())"
                    + " + ':' + (#keyword == null ? '-' : #keyword)"
                    + " + ':' + (#brand == null ? '-' : #brand)"
                    + " + ':' + (#gender == null ? '-' : #gender)"
                    + " + ':' + (#color == null ? '-' : #color)"
                    + " + ':' + (#minPrice == null ? '-' : #minPrice)"
                    + " + ':' + (#maxPrice == null ? '-' : #maxPrice)"
    )
    public ProductPageResponse get(
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
        Pageable pageable = PageRequest.of(page, size);
        Slice<ProductSummaryResponse> pageResult = productListSearcher.search(
                keyword,
                category,
                brand,
                gender,
                color,
                minPrice,
                maxPrice,
                sortBy,
                pageable
        );
        return ProductPageResponse.from(pageResult);
    }
}
