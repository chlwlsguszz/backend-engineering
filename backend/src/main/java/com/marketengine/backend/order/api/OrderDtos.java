package com.marketengine.backend.order.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderStatus;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotNull Long memberId,
            @NotNull Long productId,
            @Min(1) int quantity
    ) {
    }

    public record UpdateOrderRequest(
            @NotNull OrderStatus status,
            @Min(1) int quantity
    ) {
    }

    public record OrderResponse(
            Long id,
            Long memberId,
            Long productId,
            int quantity,
            BigDecimal unitPrice,
            OrderStatus status,
            BigDecimal totalAmount,
            OffsetDateTime createdAt
    ) {
        public static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getMember().getId(),
                    order.getProduct().getId(),
                    order.getQuantity(),
                    order.getUnitPrice(),
                    order.getStatus(),
                    order.getTotalAmount(),
                    order.getCreatedAt()
            );
        }
    }
}