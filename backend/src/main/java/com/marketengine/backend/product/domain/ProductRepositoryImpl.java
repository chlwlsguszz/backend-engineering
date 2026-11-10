package com.marketengine.backend.product.domain;

import java.math.BigDecimal;
import java.util.List;

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

    private final JPAQueryFactory queryFactory;

    public ProductRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Slice<Product> search(ProductListQuery query) {
        QProduct product = QProduct.product;
        Pageable pageable = query.pageable();

        List<Product> rows = queryFactory
                .selectFrom(product)
                .where(
                        keywordContains(query.keyword()),
                        query.category() == null ? null : product.category.eq(query.category()),
                        query.brand() == null ? null : product.brand.eq(query.brand()),
                        query.gender() == null ? null : product.gender.eq(query.gender()),
                        query.color() == null ? null : product.color.eq(query.color()),
                        minPriceGoe(query.minPrice()),
                        maxPriceLoe(query.maxPrice())
                )
                .orderBy(orderSpecifiers(query.sortBy()))
                .offset(pageable.getOffset())
                .limit(query.fetchSize())
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
        BooleanExpression combined = null;
        for (String token : ProductSearchKeywords.tokens(keyword)) {
            BooleanExpression tokenMatch = keywordTokenMatch(token);
            combined = combined == null ? tokenMatch : combined.and(tokenMatch);
        }
        return combined;
    }

    private BooleanExpression keywordTokenMatch(String token) {
        String literal = token.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return Expressions.booleanTemplate(
                "lower({0}) like {1} escape '!'",
                QProduct.product.name,
                "%" + literal + "%"
        );
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

}
