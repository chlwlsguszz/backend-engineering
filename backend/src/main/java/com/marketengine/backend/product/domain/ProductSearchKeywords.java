package com.marketengine.backend.product.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Keyword tokens shared by product search and its cache key. */
public final class ProductSearchKeywords {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MIN_TOKEN_LENGTH = 2;

    private ProductSearchKeywords() {
    }

    public static List<String> tokens(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return List.of();
        }
        return WHITESPACE.splitAsStream(keyword.trim().toLowerCase(Locale.ROOT))
                .filter(token -> token.length() >= MIN_TOKEN_LENGTH)
                .toList();
    }
}
