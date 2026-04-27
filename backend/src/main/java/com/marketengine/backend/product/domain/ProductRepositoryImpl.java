package com.marketengine.backend.product.domain;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

public class ProductRepositoryImpl implements ProductQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ProductRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<Product> search(
            String keyword,
            ProductCategory category,
            String brand,
            String gender,
            String color,
            Integer minPrice,
            Integer maxPrice,
            String sortBy,
            Pageable pageable
    ) {
        QProduct product = QProduct.product;

        List<Product> content = queryFactory
                .selectFrom(product)
                .where(
                        keywordContains(keyword),
                        categoryEq(category),
                        brandEq(brand),
                        genderEq(gender),
                        colorEq(color),
                        minPriceGoe(minPrice),
                        maxPriceLoe(maxPrice)
                )
                .orderBy(orderSpecifiers(sortBy))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(product.count())
                .from(product)
                .where(
                        keywordContains(keyword),
                        categoryEq(category),
                        brandEq(brand),
                        genderEq(gender),
                        colorEq(color),
                        minPriceGoe(minPrice),
                        maxPriceLoe(maxPrice)
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    private OrderSpecifier<?>[] orderSpecifiers(String sortBy) {
        QProduct product = QProduct.product;
        if ("POPULARITY".equalsIgnoreCase(sortBy)) {
            return new OrderSpecifier<?>[]{
                    new OrderSpecifier<>(Order.DESC, product.popularityScore),
                    new OrderSpecifier<>(Order.DESC, product.id)
            };
        }
        return new OrderSpecifier<?>[]{
                new OrderSpecifier<>(Order.DESC, product.createdAt),
                new OrderSpecifier<>(Order.DESC, product.id)
        };
    }

    private BooleanExpression keywordContains(String keyword) {
        if (!hasText(keyword)) {
            return null;
        }
        QProduct product = QProduct.product;
        return product.name.containsIgnoreCase(keyword.trim())
                .or(product.brand.containsIgnoreCase(keyword.trim()));
    }

    private BooleanExpression categoryEq(ProductCategory category) {
        if (category == null) {
            return null;
        }
        return QProduct.product.category.eq(category);
    }

    private BooleanExpression brandEq(String brand) {
        if (!hasText(brand)) {
            return null;
        }
        return QProduct.product.brand.eq(brand.trim());
    }

    private BooleanExpression genderEq(String gender) {
        if (!hasText(gender)) {
            return null;
        }
        return QProduct.product.gender.eq(gender.trim());
    }

    private BooleanExpression colorEq(String color) {
        if (!hasText(color)) {
            return null;
        }
        return QProduct.product.color.eq(color.trim());
    }

    private BooleanExpression minPriceGoe(Integer minPrice) {
        if (minPrice == null) {
            return null;
        }
        return QProduct.product.priceAmount.goe(BigDecimal.valueOf(minPrice));
    }

    private BooleanExpression maxPriceLoe(Integer maxPrice) {
        if (maxPrice == null) {
            return null;
        }
        return QProduct.product.priceAmount.loe(BigDecimal.valueOf(maxPrice));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
