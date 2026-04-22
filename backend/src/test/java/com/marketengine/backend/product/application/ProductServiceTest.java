package com.marketengine.backend.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void create_savesAndReturnsResponse() {
        CreateProductRequest request = new CreateProductRequest("keyboard", new BigDecimal("120.00"), 10);

        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = productService.create(request);

        assertThat(response.name()).isEqualTo("keyboard");
        assertThat(response.priceAmount()).isEqualByComparingTo("120.00");
        assertThat(response.stockQuantity()).isEqualTo(10);
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void get_throwsNotFoundWhenMissing() {
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.get(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void update_changesProductFields() {
        Product product = new Product("old", new BigDecimal("10.00"), 1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.update(1L, new UpdateProductRequest("new", new BigDecimal("20.00"), 2));

        assertThat(response.name()).isEqualTo("new");
        assertThat(response.priceAmount()).isEqualByComparingTo("20.00");
        assertThat(response.stockQuantity()).isEqualTo(2);
    }
}