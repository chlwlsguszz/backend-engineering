package com.marketengine.backend.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.CreateProductRequest;
import com.marketengine.backend.product.api.ProductDtos.ProductDetailResponse;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductRepository;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductSearchOutbox productSearchOutbox;

    @InjectMocks
    private ProductService productService;

    @Test
    void create_savesAndReturnsResponse() {
        CreateProductRequest request = new CreateProductRequest(
                "keyboard",
                new BigDecimal("120.00"),
                10,
                "mechanical keyboard",
                ProductCategory.TOP,
                "ACME",
                "BLACK",
                "UNISEX",
                "ACTIVE",
                800
        );

        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            ReflectionTestUtils.setField(product, "id", 41L);
            return product;
        });

        ProductDetailResponse response = productService.create(request);

        assertThat(response.name()).isEqualTo("keyboard");
        assertThat(response.priceAmount()).isEqualByComparingTo("120.00");
        assertThat(response.stockQuantity()).isEqualTo(10);
        assertThat(response.id()).isEqualTo(41L);
        InOrder writes = inOrder(productRepository, productSearchOutbox);
        writes.verify(productRepository).saveAndFlush(any(Product.class));
        writes.verify(productSearchOutbox).enqueue(41L);
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
        Product product = new Product(
                "old",
                new BigDecimal("10.00"),
                1,
                "old desc",
                ProductCategory.BOTTOM,
                "NOVA",
                "WHITE",
                "MEN",
                "ACTIVE",
                10
        );
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductDetailResponse response = productService.update(
                1L,
                new UpdateProductRequest(
                        "new",
                        new BigDecimal("20.00"),
                        2,
                        "new desc",
                        ProductCategory.OUTER,
                        "ZEN",
                        "NAVY",
                        "WOMEN",
                        "ACTIVE",
                        500
                )
        );

        assertThat(response.name()).isEqualTo("new");
        assertThat(response.priceAmount()).isEqualByComparingTo("20.00");
        assertThat(response.stockQuantity()).isEqualTo(2);
        assertThat(response.category()).isEqualTo(ProductCategory.OUTER);
        assertThat(response.popularityScore()).isEqualTo(500);
        InOrder writes = inOrder(productRepository, productSearchOutbox);
        writes.verify(productRepository).flush();
        writes.verify(productSearchOutbox).enqueue(1L);
    }

    @Test
    void delete_enqueuesAfterDeleteWasFlushed() {
        Product product = mock(Product.class);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));

        productService.delete(7L);

        InOrder writes = inOrder(productRepository, productSearchOutbox);
        writes.verify(productRepository).delete(product);
        writes.verify(productRepository).flush();
        writes.verify(productSearchOutbox).enqueue(7L);
    }

    @Test
    void delete_doesNotEnqueueWhenForeignKeyPreventsDelete() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(mock(Product.class)));
        doThrow(new DataIntegrityViolationException("product has orders")).when(productRepository).flush();

        assertThatThrownBy(() -> productService.delete(7L)).isInstanceOf(DataIntegrityViolationException.class);

        verifyNoInteractions(productSearchOutbox);
    }
}
