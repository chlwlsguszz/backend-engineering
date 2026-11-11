package com.marketengine.backend.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Immutable replay data; a duplicate request never needs to load product or member entities. */
public record OrderReplay(
        Long id,
        Long memberId,
        Long productId,
        int requestedQuantity,
        int quantity,
        BigDecimal unitPrice,
        OrderStatus status,
        BigDecimal totalAmount,
        String idempotencyKey,
        OffsetDateTime createdAt
) {}
