package com.marketengine.backend.product.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAmount;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Product() {
    }

    public Product(String name, BigDecimal priceAmount, int stockQuantity) {
        this.name = name;
        this.priceAmount = priceAmount;
        this.stockQuantity = stockQuantity;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void changeInfo(String name, BigDecimal priceAmount, int stockQuantity) {
        this.name = name;
        this.priceAmount = priceAmount;
        this.stockQuantity = stockQuantity;
    }
}
