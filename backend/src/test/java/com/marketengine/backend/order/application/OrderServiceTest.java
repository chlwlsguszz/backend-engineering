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
import com.marketengine.backend.member.domain.MemberRepository;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void create_buildsOrderWithProductPrice() {
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

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.create(new CreateOrderRequest(1L, 2L, 3));

        assertThat(response.unitPrice()).isEqualByComparingTo("30.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("90.00");
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void create_throwsNotFoundWhenMemberMissing() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(1L, 2L, 1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
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
        Order order = new Order(member, product, 1, new BigDecimal("30.00"), OrderStatus.CREATED);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.update(10L, new UpdateOrderRequest(OrderStatus.CONFIRMED, 2));

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.totalAmount()).isEqualByComparingTo("60.00");
    }
}