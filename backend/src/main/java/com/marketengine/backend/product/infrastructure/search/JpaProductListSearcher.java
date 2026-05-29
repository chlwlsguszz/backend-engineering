package com.marketengine.backend.product.infrastructure.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.application.ProductListSearcher;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * Fallback list/search using PostgreSQL (QueryDSL via {@link com.marketengine.backend.product.domain.ProductRepositoryImpl}).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "marketengine.search", name = "backend", havingValue = "postgres")
public class JpaProductListSearcher implements ProductListSearcher {

    private final ProductRepository productRepository;

    @Override
    @Transactional(readOnly = true)
    public Slice<ProductSummaryResponse> search(
            String keyword,
            ProductCategory category,
            String brand,
            String gender,
            String color,
            Integer minPrice,
            Integer maxPrice,
            String sortBy,
            Pageable pageable
    ) {
        return productRepository.search(
                keyword,
                category,
                brand,
                gender,
                color,
                minPrice,
                maxPrice,
                sortBy,
                pageable
        ).map(ProductSummaryResponse::from);
    }
}
