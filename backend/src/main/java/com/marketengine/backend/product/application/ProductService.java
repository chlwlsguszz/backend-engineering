package com.marketengine.backend.product.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
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

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductDetailResponse create(CreateProductRequest request) {
        Product saved = productRepository.save(
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
        Sort sort = "POPULARITY".equalsIgnoreCase(sortBy)
                ? Sort.by(Sort.Direction.DESC, "popularityScore").and(Sort.by(Sort.Direction.DESC, "id"))
                : Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Pageable pageable = PageRequest.of(page, size, sort);

        Specification<Product> spec = (root, query, cb) -> cb.conjunction();
        if (hasText(keyword)) {
            String normalizedKeyword = "%" + keyword.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), normalizedKeyword),
                    cb.like(cb.lower(root.get("brand")), normalizedKeyword)
            ));
        }
        if (category != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (hasText(brand)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("brand"), brand.trim()));
        }
        if (hasText(gender)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("gender"), gender.trim()));
        }
        if (hasText(color)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("color"), color.trim()));
        }
        if (minPrice != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("priceAmount"), java.math.BigDecimal.valueOf(minPrice)));
        }
        if (maxPrice != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("priceAmount"), java.math.BigDecimal.valueOf(maxPrice)));
        }

        Page<ProductSummaryResponse> pageResult = productRepository.findAll(spec, pageable).map(ProductSummaryResponse::from);
        return ProductPageResponse.from(pageResult);
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
        return ProductDetailResponse.from(product);
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}