package com.marketengine.backend.product.api;

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
import lombok.RequiredArgsConstructor;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductDetailResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.application.ProductService;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductListQuery;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ApiResponse<ProductDetailResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.ok(productService.create(request));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> get(@PathVariable Long productId) {
        return ApiResponse.ok(productService.get(productId));
    }

    @GetMapping
    @Operation(summary = "상품 목록 검색", description = """
            page는 0부터 시작하며 size는 1~100, 시작 위치는 10,000 미만입니다.
            LATEST와 POPULARITY 모두 동점이면 상품 ID 내림차순입니다.
            검색어는 소문자 공백 토큰을 AND 결합하고 한 글자 토큰은 제외합니다.
            PostgreSQL은 리터럴 부분 문자열, Elasticsearch는 이름의 분석된 토큰을 조회합니다.
            브랜드, 성별, 색상은 앞뒤 공백을 제거한 뒤 대소문자를 구분해 비교합니다.
            """)
    public ApiResponse<ProductPageResponse> list(
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
        return ApiResponse.ok(productService.list(new ProductListQuery(keyword, category, brand, gender, color,
                minPrice, maxPrice, sortBy, page == null ? 0 : page, size == null ? 12 : size)));
    }

    @PutMapping("/{productId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Replace product details using the version from GET",
            description = "A concurrent inventory or metadata change returns 409. Reload before retrying.")
    public ApiResponse<ProductDetailResponse> update(
            @PathVariable Long productId,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        return ApiResponse.ok(productService.update(productId, request));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Void> delete(@PathVariable Long productId) {
        productService.delete(productId);
        return ApiResponse.ok();
    }
}
