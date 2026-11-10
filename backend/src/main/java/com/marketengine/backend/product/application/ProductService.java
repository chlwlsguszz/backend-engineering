package com.marketengine.backend.product.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductDetailResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductRepository;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductListSearcher productListSearcher;
    private final ProductSearchOutbox productSearchOutbox;
    private final ProductListFeedCache productListFeedCache;

    @Transactional
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
        productSearchOutbox.enqueue(saved.getId());
        return ProductDetailResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse get(Long productId) {
        return ProductDetailResponse.from(findProduct(productId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        return ProductListFeedKeyResolver.resolve(
                        keyword, category, brand, gender, color, minPrice, maxPrice, sortBy, page, size
                )
                .map(productListFeedCache::get)
                .orElseGet(() -> searchProducts(
                        keyword, category, brand, gender, color, minPrice, maxPrice, sortBy, page, size
                ));
    }

    @Transactional
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
        productSearchOutbox.enqueue(productId);
        return ProductDetailResponse.from(product);
    }

    @Transactional
    public void delete(Long productId) {
        Product product = findProduct(productId);
        productRepository.delete(product);
        productRepository.flush();
        productSearchOutbox.enqueue(productId);
    }

    private ProductPageResponse searchProducts(
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

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found"));
    }
}
