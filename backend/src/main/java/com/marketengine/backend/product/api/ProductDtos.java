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
            @Min(0) int stockQuantity
    ) {
    }

    public record UpdateProductRequest(
            @NotBlank String name,
            @NotNull @PositiveOrZero BigDecimal priceAmount,
            @Min(0) int stockQuantity
    ) {
    }

    public record ProductResponse(
            Long id,
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            OffsetDateTime createdAt
    ) {
        public static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.getId(),
                    product.getName(),
                    product.getPriceAmount(),
                    product.getStockQuantity(),
                    product.getCreatedAt()
            );
        }
    }
}