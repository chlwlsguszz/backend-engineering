package com.marketengine.backend.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;

class ProductStockTest {

    @Test
    void decreaseStock_reducesQuantity() {
        Product product = sampleProduct(10);

        product.decreaseStock(1);

        assertThat(product.getStockQuantity()).isEqualTo(9);
    }

    @Test
    void decreaseStock_throwsWhenInsufficient() {
        Product product = sampleProduct(1);

        assertThatThrownBy(() -> product.decreaseStock(2))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        assertThat(product.getStockQuantity()).isEqualTo(1);
    }

    private static Product sampleProduct(int stockQuantity) {
        return new Product(
                "sample",
                new BigDecimal("10.00"),
                stockQuantity,
                "detail",
                ProductCategory.TOP,
                "BRAND",
                "BLACK",
                "UNISEX",
                "ACTIVE",
                0
        );
    }
}
