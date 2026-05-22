package com.marketengine.backend.product.infrastructure.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import com.marketengine.backend.product.domain.Product;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Elasticsearch read model for product list/search (step 2: mapping only; no queries yet).
 * Document id equals PostgreSQL {@link Product#getId()}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(indexName = ProductDocument.INDEX_NAME)
@Setting(replicas = 0)
public class ProductDocument {

    public static final String INDEX_NAME = "products";

    @Id
    private Long id;

    @Field(type = FieldType.Text)
    private String name;

    @Field(type = FieldType.Double)
    private BigDecimal priceAmount;

    @Field(type = FieldType.Integer)
    private int stockQuantity;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Keyword)
    private String color;

    @Field(type = FieldType.Keyword)
    private String gender;

    @Field(type = FieldType.Integer)
    private int popularityScore;

    @Field(type = FieldType.Date)
    private OffsetDateTime createdAt;

    public ProductDocument(
            Long id,
            String name,
            BigDecimal priceAmount,
            int stockQuantity,
            String category,
            String brand,
            String color,
            String gender,
            int popularityScore,
            OffsetDateTime createdAt
    ) {
        this.id = id;
        this.name = name;
        this.priceAmount = priceAmount;
        this.stockQuantity = stockQuantity;
        this.category = category;
        this.brand = brand;
        this.color = color;
        this.gender = gender;
        this.popularityScore = popularityScore;
        this.createdAt = createdAt;
    }

    public static ProductDocument from(Product product) {
        return new ProductDocument(
                product.getId(),
                product.getName(),
                product.getPriceAmount(),
                product.getStockQuantity(),
                product.getCategory().name(),
                product.getBrand(),
                product.getColor(),
                product.getGender(),
                product.getPopularityScore(),
                product.getCreatedAt()
        );
    }
}
