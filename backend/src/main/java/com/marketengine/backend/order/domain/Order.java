package com.marketengine.backend.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.marketengine.backend.member.domain.Member;
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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

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

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Order(Member member, Product product, int quantity, BigDecimal unitPrice, OrderStatus status) {
        this.member = member;
        this.product = product;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.status = status;
        this.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public void changeStatus(OrderStatus status) {
        this.status = status;
    }

    public void changeQuantity(int quantity) {
        this.quantity = quantity;
        this.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
