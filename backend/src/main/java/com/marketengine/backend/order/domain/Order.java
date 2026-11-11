package com.marketengine.backend.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.domain.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "orders",
        uniqueConstraints = @UniqueConstraint(
                name = Order.IDEMPOTENCY_CONSTRAINT,
                columnNames = {"member_id", "idempotency_key"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    public static final String IDEMPOTENCY_CONSTRAINT = "uk_orders_member_idempotency_key";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "requested_quantity", nullable = false, updatable = false)
    private int requestedQuantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Order(
            Member member,
            Product product,
            int quantity,
            BigDecimal unitPrice,
            OrderStatus status,
            String idempotencyKey
    ) {
        this.member = member;
        this.product = product;
        this.quantity = quantity;
        this.requestedQuantity = quantity;
        this.unitPrice = unitPrice;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    @PrePersist
    void assignCreatedAt() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** Positive delta reserves additional units; a negative delta releases units. */
    public int revise(OrderStatus next, int nextQuantity) {
        if (next == null || nextQuantity < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "A status and positive quantity are required");
        }
        if ((status == OrderStatus.CANCELLED && next != OrderStatus.CANCELLED)
                || (next == OrderStatus.CANCELLED && nextQuantity != quantity)
                || (status == OrderStatus.CONFIRMED && next == OrderStatus.CREATED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Invalid order transition");
        }
        int reservedBefore = status == OrderStatus.CANCELLED ? 0 : quantity;
        int reservedAfter = next == OrderStatus.CANCELLED ? 0 : nextQuantity;
        this.quantity = nextQuantity;
        this.status = next;
        this.totalAmount = unitPrice.multiply(BigDecimal.valueOf(nextQuantity));
        return reservedAfter - reservedBefore;
    }
}
