package com.marketengine.backend.product.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.common.config.RedisCacheConfig;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductDetailResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductRepository;
import com.marketengine.backend.product.infrastructure.search.ProductSearchIndexer;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductListSearcher productListSearcher;
    private final ProductSearchIndexer productSearchIndexer;
    private final ProductListFeedCache productListFeedCache;

    @Transactional
    @CacheEvict(cacheNames = RedisCacheConfig.PRODUCT_LIST_FEED, allEntries = true)
    public ProductDetailResponse create(CreateProductRequest request) {
        Product saved = productRepository.saveAndFlush(
                new Product(
                        request.name(),
                        request.priceAmount(),
                        request.stockQuantity(),
                        request.description(),
                        request.category(),
                        request.brand(),
                        request.color(),
                        request.gender(),
                        request.status(),
                        request.popularityScore()
                )
        );
        productSearchIndexer.index(saved);
        return ProductDetailResponse.from(saved);
    }

    public ProductDetailResponse get(Long productId) {
        return ProductDetailResponse.from(findProduct(productId));
    }

    public ProductPageResponse list(
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
        if (isCacheableFeedRequest(keyword, category, brand, gender, color, minPrice, maxPrice, sortBy, page, size)) {
            return productListFeedCache.get(keyword, category, minPrice, maxPrice, sortBy, page, size);
        }
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

    @Transactional
    @CacheEvict(cacheNames = RedisCacheConfig.PRODUCT_LIST_FEED, allEntries = true)
    public ProductDetailResponse update(Long productId, UpdateProductRequest request) {
        Product product = findProduct(productId);
        product.changeInfo(
                request.name(),
                request.priceAmount(),
                request.stockQuantity(),
                request.description(),
                request.category(),
                request.brand(),
                request.color(),
                request.gender(),
                request.status(),
                request.popularityScore()
        );
        productRepository.flush();
        productSearchIndexer.index(product);
        return ProductDetailResponse.from(product);
    }

    @Transactional
    @CacheEvict(cacheNames = RedisCacheConfig.PRODUCT_LIST_FEED, allEntries = true)
    public void delete(Long productId) {
        Product product = findProduct(productId);
        productRepository.delete(product);
        productRepository.flush();
        productSearchIndexer.delete(productId);
    }

    private boolean isCacheableFeedRequest(
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
        if (page != 0 || size != 12) {
            return false;
        }
        if (!("LATEST".equals(sortBy) || "POPULARITY".equals(sortBy))) {
            return false;
        }
        if (brand != null || gender != null || color != null) {
            return false;
        }

        // Aggressive but bounded: cache common top-page patterns used by k6.
        // - no filters
        // - category only
        // - keyword only
        // - price band only
        // - keyword + price band
        // - keyword + category
        boolean hasKeyword = keyword != null;
        boolean hasCategory = category != null;
        boolean hasPriceBand = minPrice != null || maxPrice != null;

        if (!hasKeyword && !hasCategory && !hasPriceBand) {
            return true;
        }

        if (hasCategory && !hasKeyword && !hasPriceBand) {
            return true;
        }

        if (hasKeyword && !hasCategory && !hasPriceBand) {
            return true;
        }

        if (!hasKeyword && !hasCategory && hasPriceBand) {
            return true;
        }

        if (hasKeyword && !hasCategory && hasPriceBand) {
            return true;
        }

        if (hasKeyword && hasCategory && !hasPriceBand) {
            return true;
        }

        return false;
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found"));
    }
}