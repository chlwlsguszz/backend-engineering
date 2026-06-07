package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.member.domain.MemberRepository;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private OrderCreateExecutor orderCreateExecutor;

    @InjectMocks
    private OrderService orderService;

    @Test
    void create_delegatesToExecutorWhenIdempotencyKeyIsNew() {
        Member member = new Member("user@test.com", "pw", "user");
        CreateOrderRequest request = new CreateOrderRequest(1L, 2L, 3, "key-1");
        OrderResponse expected = new OrderResponse(
                99L,
                1L,
                2L,
                3,
                new BigDecimal("30.00"),
                OrderStatus.CREATED,
                new BigDecimal("90.00"),
                "key-1",
                null
        );

        when(orderRepository.findByMemberIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.empty());
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(orderCreateExecutor.execute(request, member)).thenReturn(expected);

        OrderResponse response = orderService.create(request);

        assertThat(response).isEqualTo(expected);
        verify(orderCreateExecutor).execute(request, member);
    }

    @Test
    void create_returnsExistingOrderWithoutCallingExecutorWhenIdempotencyKeyMatches() {
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
        ReflectionTestUtils.setField(product, "id", 2L);
        Order existing = new Order(member, product, 3, new BigDecimal("30.00"), OrderStatus.CREATED, "key-1");
        CreateOrderRequest request = new CreateOrderRequest(1L, 2L, 3, "key-1");

        when(orderRepository.findByMemberIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.of(existing));

        OrderResponse response = orderService.create(request);

        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.idempotencyKey()).isEqualTo("key-1");
        verify(orderCreateExecutor, never()).execute(any(), any());
        verify(memberRepository, never()).findById(any());
    }

    @Test
    void create_throwsWhenIdempotencyKeyReusedWithDifferentPayload() {
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
        ReflectionTestUtils.setField(product, "id", 2L);
        Order existing = new Order(member, product, 3, new BigDecimal("30.00"), OrderStatus.CREATED, "key-1");

        when(orderRepository.findByMemberIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(1L, 2L, 5, "key-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
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
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.update(10L, new UpdateOrderRequest(OrderStatus.CONFIRMED, 2));

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.totalAmount()).isEqualByComparingTo("60.00");
    }
}
