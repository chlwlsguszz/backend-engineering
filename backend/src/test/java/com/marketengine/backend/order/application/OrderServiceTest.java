package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.infrastructure.ProductInventory;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderCreateExecutor orderCreateExecutor;

    @Mock
    private ProductInventory inventory;

    @Mock
    private ProductSearchOutbox outbox;

    @InjectMocks
    private OrderService orderService;

    @Test
    void create_returnsExecutorResultWithoutRecoveryLookup() {
        var request = new CreateOrderRequest(1L, 2L, 3, "key-1");
        var expected = mock(OrderResponse.class);
        when(orderCreateExecutor.execute(request)).thenReturn(expected);

        assertThat(orderService.create(request)).isSameAs(expected);

        verify(orderCreateExecutor).execute(request);
        verifyNoMoreInteractions(orderCreateExecutor);
    }

    @Test
    void create_preservesInsufficientStockWhenNoConcurrentOrderExists() {
        var request = new CreateOrderRequest(1L, 1001L, 1, "last-unit");
        var failure = new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "No units available");
        when(orderCreateExecutor.execute(request)).thenThrow(failure);
        when(orderCreateExecutor.findExistingResponse(request)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.create(request)).isSameAs(failure);
    }

    @Test
    void create_propagatesPayloadConflictFoundDuringRecoveryAfterInsufficientStock() {
        var request = new CreateOrderRequest(1L, 1001L, 2, "last-unit");
        var conflict = new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        when(orderCreateExecutor.execute(request)).thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));
        when(orderCreateExecutor.findExistingResponse(request)).thenThrow(conflict);

        assertThatThrownBy(() -> orderService.create(request)).isSameAs(conflict);
    }

    @Test
    void create_propagatesUniqueViolationWithoutPostgresConstraintMetadata() {
        var request = new CreateOrderRequest(1L, 1001L, 1, "duplicate");
        var failure = new DataIntegrityViolationException("order insert failed",
                new SQLException("duplicate key: uk_orders_member_idempotency_key", "23505"));
        when(orderCreateExecutor.execute(request)).thenThrow(failure);

        assertThatThrownBy(() -> orderService.create(request)).isSameAs(failure);
        verify(orderCreateExecutor).execute(request);
        verifyNoMoreInteractions(orderCreateExecutor);
    }

    @Test
    void update_changesStatusAndQuantity() {
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
        Order order = new Order(member, product, 1, new BigDecimal("30.00"), OrderStatus.CREATED, "key-1");
        ReflectionTestUtils.setField(product, "id", 2L);
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.update(10L, new UpdateOrderRequest(OrderStatus.CONFIRMED, 2));

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.totalAmount()).isEqualByComparingTo("60.00");
        verify(inventory).reserve(2L, 1);
        verify(outbox).enqueue(2L);
    }
}
