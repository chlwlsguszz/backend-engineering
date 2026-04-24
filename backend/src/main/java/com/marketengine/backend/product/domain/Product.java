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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "popularity_score", nullable = false)
    private int popularityScore;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Product() {
    }

    public Product(
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            String description,
            String category,
            String brand,
            String status,
            int popularityScore
    ) {
        this.name = name;
        this.priceAmount = priceAmount;
        this.stockQuantity = stockQuantity;
        this.description = description;
        this.category = category;
        this.brand = brand;
        this.status = status;
        this.popularityScore = popularityScore;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getBrand() {
        return brand;
    }

    public String getStatus() {
        return status;
    }

    public int getPopularityScore() {
        return popularityScore;
    }

    public void changeInfo(
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            String description,
            String category,
            String brand,
            String status,
            int popularityScore
    ) {
        this.name = name;
        this.priceAmount = priceAmount;
        this.stockQuantity = stockQuantity;
        this.description = description;
        this.category = category;
        this.brand = brand;
        this.status = status;
        this.popularityScore = popularityScore;
    }
}
