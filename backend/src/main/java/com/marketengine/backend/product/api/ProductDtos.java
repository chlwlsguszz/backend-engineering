package com.marketengine.backend.product.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.marketengine.backend.product.domain.Product;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(
            @NotBlank String name,
            @NotNull @PositiveOrZero BigDecimal priceAmount,
            @Min(0) int stockQuantity,
            @NotBlank String description,
            @NotBlank String category,
            @NotBlank String brand,
            @NotBlank String status,
            @Min(0) int popularityScore
    ) {
    }

    public record UpdateProductRequest(
            @NotBlank String name,
            @NotNull @PositiveOrZero BigDecimal priceAmount,
            @Min(0) int stockQuantity,
            @NotBlank String description,
            @NotBlank String category,
            @NotBlank String brand,
            @NotBlank String status,
            @Min(0) int popularityScore
    ) {
    }

    public record ProductSummaryResponse(
            Long id,
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            String category,
            String brand,
            int popularityScore,
            OffsetDateTime createdAt
    ) {
        public static ProductSummaryResponse from(Product product) {
            return new ProductSummaryResponse(
                    product.getId(),
                    product.getName(),
                    product.getPriceAmount(),
                    product.getStockQuantity(),
                    product.getCategory(),
                    product.getBrand(),
                    product.getPopularityScore(),
                    product.getCreatedAt()
            );
        }
    }

    public record ProductDetailResponse(
            Long id,
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            String description,
            String category,
            String brand,
            String status,
            int popularityScore,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static ProductDetailResponse from(Product product) {
            return new ProductDetailResponse(
                    product.getId(),
                    product.getName(),
                    product.getPriceAmount(),
                    product.getStockQuantity(),
                    product.getDescription(),
                    product.getCategory(),
                    product.getBrand(),
                    product.getStatus(),
                    product.getPopularityScore(),
                    product.getCreatedAt(),
                    product.getUpdatedAt()
            );
        }
    }
}