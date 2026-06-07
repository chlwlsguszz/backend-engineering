package com.marketengine.backend.order.application;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.member.domain.MemberRepository;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final OrderCreateExecutor orderCreateExecutor;

    public OrderResponse create(CreateOrderRequest request) {
        return orderRepository.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey())
                .map(existing -> OrderResponse.from(validateSameRequest(existing, request)))
                .orElseGet(() -> createNewOrder(request));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long orderId) {
        return OrderResponse.from(findOrder(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list() {
        return orderRepository.findAll().stream().map(OrderResponse::from).toList();
    }

    @Transactional
    public OrderResponse update(Long orderId, UpdateOrderRequest request) {
        Order order = findOrder(orderId);
        order.changeStatus(request.status());
        order.changeQuantity(request.quantity());
        return OrderResponse.from(order);
    }

    @Transactional
    public void delete(Long orderId) {
        Order order = findOrder(orderId);
        orderRepository.delete(order);
    }

    private OrderResponse createNewOrder(CreateOrderRequest request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Member not found"));

        try {
            return orderCreateExecutor.execute(request, member);
        } catch (DataIntegrityViolationException ex) {
            if (!isIdempotencyKeyViolation(ex)) {
                throw ex;
            }
            Order existing = orderRepository.findByMemberIdAndIdempotencyKey(
                            request.memberId(),
                            request.idempotencyKey()
                    )
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONFLICT,
                            "Duplicate order request is still being processed"
                    ));
            return OrderResponse.from(validateSameRequest(existing, request));
        }
    }

    private Order validateSameRequest(Order existing, CreateOrderRequest request) {
        if (existing.getProduct().getId() != request.productId()
                || existing.getQuantity() != request.quantity()) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "Idempotency key already used with a different request"
            );
        }
        return existing;
    }

    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause != null ? cause.getMessage() : ex.getMessage();
        return message != null && message.toLowerCase().contains("idempotency");
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
    }
}
