package com.marketengine.backend.product.application;

import java.util.Optional;

import com.marketengine.backend.product.domain.ProductCategory;

/**
 * Maps list API requests to fixed feed cache keys when they match a cacheable feed pattern.
 */
public final class ProductListFeedKeyResolver {

    static final int FEED_PAGE_SIZE = 12;
    static final int MAX_CACHED_PAGE = 2;

    private ProductListFeedKeyResolver() {
    }

    public static Optional<ProductListFeedKey> resolve(
            String keyword,
            ProductCategory category,
            String brand,
            String gender,
            String color,
            Integer minPrice,
            Integer maxPrice,
            String sortBy,
            int page,
            int size
    ) {
        if (size != FEED_PAGE_SIZE || page < 0 || page > MAX_CACHED_PAGE) {
            return Optional.empty();
        }
        if (!("LATEST".equals(sortBy) || "POPULARITY".equals(sortBy))) {
            return Optional.empty();
        }

        boolean hasKeyword = hasText(keyword);
        boolean hasCategory = category != null;
        boolean hasBrand = hasText(brand);
        boolean hasGender = hasText(gender);
        boolean hasColor = hasText(color);
        boolean hasPriceBand = minPrice != null || maxPrice != null;

        ProductListFeedQuery query = new ProductListFeedQuery(
                keyword,
                category,
                brand,
                gender,
                color,
                minPrice,
                maxPrice,
                sortBy,
                page,
                size
        );

        String sort = sortSegment(sortBy);

        if (!hasBrand && !hasGender && !hasColor) {
            return resolveWithoutFacetFilters(
                    hasKeyword, hasCategory, hasPriceBand, query, sort, page, category, keyword, minPrice, maxPrice
            );
        }

        return resolveWithFacetFilters(
                hasKeyword,
                hasCategory,
                hasBrand,
                hasGender,
                hasColor,
                hasPriceBand,
                query,
                sort,
                page,
                category,
                brand,
                gender,
                color,
                keyword,
                minPrice,
                maxPrice
        );
    }

    private static Optional<ProductListFeedKey> resolveWithoutFacetFilters(
            boolean hasKeyword,
            boolean hasCategory,
            boolean hasPriceBand,
            ProductListFeedQuery query,
            String sort,
            int page,
            ProductCategory category,
            String keyword,
            Integer minPrice,
            Integer maxPrice
    ) {
        if (!hasKeyword && !hasCategory && !hasPriceBand) {
            return key(query, "feed:home:" + sort + ":" + page);
        }
        if (hasCategory && !hasKeyword && !hasPriceBand) {
            return key(query, "feed:category:" + category.name() + ":" + sort + ":" + page);
        }
        if (hasKeyword && !hasCategory && !hasPriceBand) {
            return key(query, "feed:keyword:" + segment(keyword) + ":" + sort + ":" + page);
        }
        if (!hasKeyword && !hasCategory && hasPriceBand) {
            return key(query, "feed:price:" + priceSegment(minPrice, maxPrice) + ":" + sort + ":" + page);
        }
        if (hasKeyword && !hasCategory && hasPriceBand) {
            return key(
                    query,
                    "feed:keyword-price:" + segment(keyword) + ":" + priceSegment(minPrice, maxPrice)
                            + ":" + sort + ":" + page
            );
        }
        if (hasKeyword && hasCategory && !hasPriceBand) {
            return key(
                    query,
                    "feed:keyword-category:" + segment(keyword) + ":" + category.name() + ":" + sort + ":" + page
            );
        }
        return Optional.empty();
    }

    private static Optional<ProductListFeedKey> resolveWithFacetFilters(
            boolean hasKeyword,
            boolean hasCategory,
            boolean hasBrand,
            boolean hasGender,
            boolean hasColor,
            boolean hasPriceBand,
            ProductListFeedQuery query,
            String sort,
            int page,
            ProductCategory category,
            String brand,
            String gender,
            String color,
            String keyword,
            Integer minPrice,
            Integer maxPrice
    ) {
        if (hasCategory && hasBrand && !hasKeyword && !hasPriceBand && !hasGender && !hasColor) {
            return key(
                    query,
                    "feed:category-brand:" + category.name() + ":" + segment(brand) + ":" + sort + ":" + page
            );
        }
        if (hasCategory && hasGender && hasColor && !hasKeyword && !hasBrand && !hasPriceBand) {
            return key(
                    query,
                    "feed:category-gender-color:" + category.name() + ":" + segment(gender) + ":"
                            + segment(color) + ":" + sort + ":" + page
            );
        }
        if (hasBrand && hasGender && !hasCategory && !hasKeyword && !hasPriceBand && !hasColor) {
            return key(
                    query,
                    "feed:brand-gender:" + segment(brand) + ":" + segment(gender) + ":" + sort + ":" + page
            );
        }
        if (hasCategory && hasBrand && hasGender && hasColor && hasPriceBand && !hasKeyword) {
            return key(
                    query,
                    "feed:category-facet:" + category.name() + ":" + segment(brand) + ":"
                            + segment(gender) + ":" + segment(color) + ":"
                            + priceSegment(minPrice, maxPrice) + ":" + sort + ":" + page
            );
        }
        if (hasKeyword && hasCategory && hasBrand && hasGender && hasColor && hasPriceBand) {
            return key(
                    query,
                    "feed:full:" + segment(keyword) + ":" + category.name() + ":" + segment(brand) + ":"
                            + segment(gender) + ":" + segment(color) + ":"
                            + priceSegment(minPrice, maxPrice) + ":" + sort + ":" + page
            );
        }
        return Optional.empty();
    }

    private static Optional<ProductListFeedKey> key(ProductListFeedQuery query, String value) {
        return Optional.of(new ProductListFeedKey(value, query));
    }

    private static String sortSegment(String sortBy) {
        return "POPULARITY".equals(sortBy) ? "popularity" : "latest";
    }

    private static String priceSegment(Integer minPrice, Integer maxPrice) {
        return (minPrice == null ? "-" : minPrice) + ":" + (maxPrice == null ? "-" : maxPrice);
    }

    private static String segment(String value) {
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
