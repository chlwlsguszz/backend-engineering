package com.marketengine.backend.product.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

public class ProductRepositoryImpl implements ProductQueryRepository {

    private static final int KEYWORD_MIN_LENGTH = 2;

    /** pg_trgm word_similarity; typo tolerance (e.g. Nikke → Nike). Requires PostgreSQL + pg_trgm. */
    private static final double WORD_SIMILARITY_THRESHOLD = 0.35;

    private final JPAQueryFactory queryFactory;

    public ProductRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Slice<Product> search(
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

        List<Product> rows = queryFactory
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
                .limit(pageable.getPageSize() + 1L)
                .fetch();

        boolean hasNext = rows.size() > pageable.getPageSize();
        if (hasNext) {
            rows.remove(rows.size() - 1);
        }

        return new SliceImpl<>(rows, pageable, hasNext);
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
        String[] tokens = keyword.trim().toLowerCase(Locale.ROOT).split("\\s+");
        BooleanExpression combined = null;
        for (String token : tokens) {
            if (token.length() < KEYWORD_MIN_LENGTH) {
                continue;
            }
            BooleanExpression tokenMatch = keywordTokenMatch(token);
            combined = combined == null ? tokenMatch : combined.and(tokenMatch);
        }
        return combined;
    }

    private BooleanExpression keywordTokenMatch(String token) {
        return Expressions.booleanTemplate(
                "(lower({0}) like {1} or word_similarity({2}, lower({0})) > {3})",
                QProduct.product.name,
                "%" + token + "%",
                token,
                WORD_SIMILARITY_THRESHOLD
        );
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
