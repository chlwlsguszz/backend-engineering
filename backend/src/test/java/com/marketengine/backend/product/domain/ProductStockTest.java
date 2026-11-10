package com.marketengine.backend.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;

class ProductStockTest {

    @Test
    void zeroStockIsDisplayedAsSoldOut() {
        assertThat(sampleProduct(0, "ACTIVE").getStatus()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void saleStatusIsValidatedAndDiscontinuationIsIndependentOfStock() {
        assertThat(sampleProduct(5, "OUT_OF_STOCK").getStatus()).isEqualTo("ACTIVE");
        assertThat(sampleProduct(5, "DISCONTINUED").getStatus()).isEqualTo("DISCONTINUED");
        assertThatThrownBy(() -> sampleProduct(1, "UNKNOWN"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private static Product sampleProduct(int stockQuantity, String status) {
        return new Product(
                "sample",
                new BigDecimal("10.00"),
                stockQuantity,
                "detail",
                ProductCategory.TOP,
                "BRAND",
                "BLACK",
                "UNISEX",
                status,
                0
        );
    }
}
