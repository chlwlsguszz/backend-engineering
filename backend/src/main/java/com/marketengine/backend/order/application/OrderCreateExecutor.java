package com.marketengine.backend.order.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.member.domain.MemberRepository;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderReplay;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductRepository;
import com.marketengine.backend.product.infrastructure.ProductInventory;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderCreateExecutor {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final ProductInventory inventory;
    private final ProductSearchOutbox productSearchOutbox;
    private final OrderTransactionLimits transactionLimits;

    @Transactional
    public OrderResponse execute(CreateOrderRequest request) {
        transactionLimits.apply();
        return findExistingResponse(request).orElseGet(() -> createNewOrder(request));
    }

    Optional<OrderResponse> findExistingResponse(CreateOrderRequest request) {
        return orderRepository.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey())
                .map(existing -> replayResponse(existing, request));
    }

    private OrderResponse replayResponse(OrderReplay existing, CreateOrderRequest request) {
        if (!request.productId().equals(existing.productId())
                || existing.requestedQuantity() != request.quantity()) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "Idempotency key already used with a different request"
            );
        }
        return new OrderResponse(existing.id(), existing.memberId(), existing.productId(), existing.quantity(),
                existing.unitPrice(), existing.status(), existing.totalAmount(), existing.idempotencyKey(),
                existing.createdAt());
    }

    private OrderResponse createNewOrder(CreateOrderRequest request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Member not found"));
        var reservation = inventory.reserve(request.productId(), request.quantity());
        Product product = productRepository.getReferenceById(request.productId());

        Order saved = orderRepository.save(
                new Order(
                        member,
                        product,
                        request.quantity(),
                        reservation.unitPrice(),
                        OrderStatus.CREATED,
                        request.idempotencyKey()
                )
        );
        if (reservation.availabilityChanged()) {
            productSearchOutbox.enqueue(request.productId());
        }
        return OrderResponse.from(saved);
    }
}
