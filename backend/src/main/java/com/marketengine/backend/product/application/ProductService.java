package com.marketengine.backend.product.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductRepository;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product saved = productRepository.save(
                new Product(request.name(), request.priceAmount(), request.stockQuantity())
        );
        return ProductResponse.from(saved);
    }

    public ProductResponse get(Long productId) {
        return ProductResponse.from(findProduct(productId));
    }

    public List<ProductResponse> list() {
        return productRepository.findAll().stream().map(ProductResponse::from).toList();
    }

    @Transactional
    public ProductResponse update(Long productId, UpdateProductRequest request) {
        Product product = findProduct(productId);
        product.changeInfo(request.name(), request.priceAmount(), request.stockQuantity());
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Long productId) {
        Product product = findProduct(productId);
        productRepository.delete(product);
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found"));
    }
}