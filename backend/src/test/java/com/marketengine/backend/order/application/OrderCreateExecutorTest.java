package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductRepository;
import com.marketengine.backend.product.infrastructure.ProductInventory;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

@ExtendWith(MockitoExtension.class)
class OrderCreateExecutorTest {

    private final Member member = new Member("user@test.com", "pw", "user");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductInventory inventory;

    @Mock
    private ProductSearchOutbox productSearchOutbox;

    @InjectMocks
    private OrderCreateExecutor orderCreateExecutor;

    @Test
    void execute_buildsOrderWithProductPrice() {
        Product product = productWithFiveUnits();
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        when(inventory.reserve(2L, 3)).thenReturn(new BigDecimal("30.00"));
        when(productRepository.getReferenceById(2L)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderCreateExecutor.execute(
                new CreateOrderRequest(1L, 2L, 3, "key-1")
        );

        assertThat(response.unitPrice()).isEqualByComparingTo("30.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("90.00");
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.idempotencyKey()).isEqualTo("key-1");
        verify(inventory).reserve(2L, 3);
        verify(productSearchOutbox).enqueue(2L);
    }

    @Test
    void execute_throwsInsufficientStockWhenQuantityExceedsStock() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        when(inventory.reserve(2L, 6)).thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));

        assertThatThrownBy(() -> orderCreateExecutor.execute(
                new CreateOrderRequest(1L, 2L, 6, "key-1")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        verifyNoInteractions(productRepository, productSearchOutbox);
    }

    @Test
    void execute_returnsExistingOrderWithoutLockingProductWhenIdempotencyKeyMatches() {
        Product product = productWithFiveUnits();
        ReflectionTestUtils.setField(product, "id", 1001L);
        var existing = new Order(member, product, 3, product.getPriceAmount(), OrderStatus.CREATED, "key-1");
        var request = new CreateOrderRequest(1L, 1001L, 3, "key-1");
        when(orderRepository.findByMemberIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.of(existing));

        OrderResponse response = orderCreateExecutor.execute(request);

        assertThat(response.productId()).isEqualTo(1001L);
        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.idempotencyKey()).isEqualTo("key-1");
        verifyNoInteractions(memberRepository, productRepository, productSearchOutbox);
    }

    @Test
    void execute_rejectsIdempotencyKeyReusedWithDifferentQuantity() {
        Product product = productWithFiveUnits();
        ReflectionTestUtils.setField(product, "id", 1001L);
        var existing = new Order(member, product, 1, product.getPriceAmount(), OrderStatus.CREATED, "key-1");
        var request = new CreateOrderRequest(1L, 1001L, 2, "key-1");
        when(orderRepository.findByMemberIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> orderCreateExecutor.execute(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
        verifyNoInteractions(memberRepository, productRepository, productSearchOutbox);
    }

    @Test
    void execute_rejectsMissingMemberBeforeLockingProduct() {
        var request = new CreateOrderRequest(1L, 1001L, 2, "key-1");
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderCreateExecutor.execute(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(productRepository, productSearchOutbox);
    }

    private Product productWithFiveUnits() {
        return new Product("book", new BigDecimal("30.00"), 5, "book detail", ProductCategory.TOP,
                "CORE", "BLACK", "UNISEX", "ACTIVE", 100);
    }
}
