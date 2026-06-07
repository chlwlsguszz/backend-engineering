package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductRepository;

@ExtendWith(MockitoExtension.class)
class OrderCreateExecutorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private OrderCreateExecutor orderCreateExecutor;

    @Test
    void execute_buildsOrderWithProductPrice() {
        Member member = new Member("user@test.com", "pw", "user");
        Product product = new Product(
                "book",
                new BigDecimal("30.00"),
                5,
                "book detail",
                ProductCategory.TOP,
                "CORE",
                "BLACK",
                "UNISEX",
                "ACTIVE",
                100
        );

        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderCreateExecutor.execute(
                new CreateOrderRequest(1L, 2L, 3, "key-1"),
                member
        );

        assertThat(response.unitPrice()).isEqualByComparingTo("30.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("90.00");
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.idempotencyKey()).isEqualTo("key-1");
        assertThat(product.getStockQuantity()).isEqualTo(2);
    }

    @Test
    void execute_throwsInsufficientStockWhenQuantityExceedsStock() {
        Member member = new Member("user@test.com", "pw", "user");
        Product product = new Product(
                "book",
                new BigDecimal("30.00"),
                5,
                "book detail",
                ProductCategory.TOP,
                "CORE",
                "BLACK",
                "UNISEX",
                "ACTIVE",
                100
        );

        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> orderCreateExecutor.execute(
                new CreateOrderRequest(1L, 2L, 6, "key-1"),
                member
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }
}
