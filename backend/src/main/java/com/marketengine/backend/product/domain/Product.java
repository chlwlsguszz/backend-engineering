package com.marketengine.backend.product.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private ProductCategory category;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(nullable = false, length = 50)
    private String color;

    @Column(nullable = false, length = 20)
    private String gender;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "popularity_score", nullable = false)
    private int popularityScore;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void created() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void updated() {
        OffsetDateTime now = OffsetDateTime.now();
        if (now.isAfter(updatedAt)) {
            updatedAt = now;
        }
    }

    public Product(
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            String description,
            ProductCategory category,
            String brand,
            String color,
            String gender,
            String status,
            int popularityScore
    ) {
        this.name = name;
        this.priceAmount = priceAmount;
        this.stockQuantity = stockQuantity;
        this.description = description;
        this.category = category;
        this.brand = brand;
        this.color = color;
        this.gender = gender;
        this.status = saleStatus(status, stockQuantity);
        this.popularityScore = popularityScore;
    }

    private static String saleStatus(String status, int stockQuantity) {
        if ("DISCONTINUED".equals(status)) {
            return status;
        }
        if (!"ACTIVE".equals(status) && !"OUT_OF_STOCK".equals(status)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Unknown product sale status");
        }
        return stockQuantity == 0 ? "OUT_OF_STOCK" : "ACTIVE";
    }

    public void changeInfo(
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            String description,
            ProductCategory category,
            String brand,
            String color,
            String gender,
            String status,
            int popularityScore
    ) {
        this.name = name;
        this.priceAmount = priceAmount;
        this.stockQuantity = stockQuantity;
        this.description = description;
        this.category = category;
        this.brand = brand;
        this.color = color;
        this.gender = gender;
        this.status = saleStatus(status, stockQuantity);
        this.popularityScore = popularityScore;
    }
}
