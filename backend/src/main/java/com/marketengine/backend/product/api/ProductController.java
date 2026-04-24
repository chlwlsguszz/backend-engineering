package com.marketengine.backend.product.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.marketengine.backend.common.response.ApiResponse;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductDetailResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.application.ProductService;
import com.marketengine.backend.product.domain.ProductCategory;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductDetailResponse>> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(productService.create(request)));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> get(@PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.ok(productService.get(productId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ProductPageResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProductCategory category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) Integer minPrice,
            @RequestParam(required = false) Integer maxPrice,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        int normalizedPage = page == null ? 0 : Math.max(page, 0);
        int normalizedSize = size == null ? 12 : Math.min(Math.max(size, 1), 100);
        String normalizedSort = sortBy == null ? "VIEW" : sortBy;
        return ResponseEntity.ok(ApiResponse.ok(productService.list(
                keyword,
                category,
                brand,
                gender,
                color,
                minPrice,
                maxPrice,
                normalizedSort,
                normalizedPage,
                normalizedSize
        )));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> update(
            @PathVariable Long productId,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(productService.update(productId, request)));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long productId) {
        productService.delete(productId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}