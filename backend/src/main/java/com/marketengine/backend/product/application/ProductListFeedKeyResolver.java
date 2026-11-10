package com.marketengine.backend.product.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductSearchKeywords;

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

        String terms = String.join(" ", ProductSearchKeywords.tokens(keyword));
        String sort = "POPULARITY".equalsIgnoreCase(sortBy) ? "POPULARITY" : "LATEST";
        String key = "feed:q1?cat=" + Objects.toString(category, "")
                + "&brand=" + encodeFacet(brand)
                + "&gender=" + encodeFacet(gender)
                + "&color=" + encodeFacet(color)
                + "&min=" + Objects.toString(minPrice, "")
                + "&max=" + Objects.toString(maxPrice, "")
                + "&kw=" + URLEncoder.encode(terms, StandardCharsets.UTF_8)
                + "&sort=" + sort + "&page=" + page + "&size=" + size;

        var query = new ProductListFeedQuery(
                keyword, category, brand, gender, color, minPrice, maxPrice, sortBy, page, size
        );
        return Optional.of(new ProductListFeedKey(key, query));
    }

    private static String encodeFacet(String value) {
        // Both search backends use exact facet values after trimming their edges.
        return URLEncoder.encode(Objects.toString(value, "").trim(), StandardCharsets.UTF_8);
    }
}
