package com.marketengine.backend.product.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marketengine.backend.common.response.ApiResponse;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.application.ProductService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(productService.create(request)));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> get(@PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.ok(productService.get(productId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(productService.list()));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> update(
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