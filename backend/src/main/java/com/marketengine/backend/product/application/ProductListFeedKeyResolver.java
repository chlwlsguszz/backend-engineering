package com.marketengine.backend.product.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import com.marketengine.backend.product.domain.ProductListQuery;

public final class ProductListFeedKeyResolver {

    static final int FEED_PAGE_SIZE = 12;
    static final int MAX_CACHED_PAGE = 2;

    private ProductListFeedKeyResolver() {
    }

    public static Optional<ProductListFeedKey> resolve(ProductListQuery query) {
        if (query.size() != FEED_PAGE_SIZE || query.page() > MAX_CACHED_PAGE) {
            return Optional.empty();
        }
        String key = "feed:q2?cat=" + Objects.toString(query.category(), "")
                + "&brand=" + encode(query.brand())
                + "&gender=" + encode(query.gender())
                + "&color=" + encode(query.color())
                + "&min=" + Objects.toString(query.minPrice(), "")
                + "&max=" + Objects.toString(query.maxPrice(), "")
                + "&kw=" + encode(query.keyword())
                + "&sort=" + query.sortBy() + "&page=" + query.page() + "&size=" + query.size();
        return Optional.of(new ProductListFeedKey(key, query));
    }

    private static String encode(String value) {
        return URLEncoder.encode(Objects.toString(value, ""), StandardCharsets.UTF_8);
    }
}
