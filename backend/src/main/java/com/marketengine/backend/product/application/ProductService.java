package com.marketengine.backend.product.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductDetailResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductListQuery;
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
    public ProductPageResponse list(ProductListQuery query) {
        return ProductListFeedKeyResolver.resolve(query)
                .map(productListFeedCache::get)
                .orElseGet(() -> ProductPageResponse.from(productListSearcher.search(query)));
    }

    @Transactional
    public ProductDetailResponse update(Long productId, UpdateProductRequest request) {
        Product product = findProduct(productId);
        if (request.version() == null || product.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product changed. Reload before updating.");
        }
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

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found"));
    }
}
