package com.marketengine.backend.product.domain;

import java.util.Locale;

import org.springframework.data.domain.PageRequest;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;

/** Validated search parameters shared by both read stores and the feed cache. */
public record ProductListQuery(String keyword, ProductCategory category, String brand, String gender,
                               String color, Integer minPrice, Integer maxPrice, String sortBy,
                               int page, int size) {

    private static final int RESULT_WINDOW = 10_000;

    public ProductListQuery {
        if (page < 0 || size < 1 || size > 100 || (long) page * size >= RESULT_WINDOW) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "Use a nonnegative page, size 1..100, and an offset below 10000");
        }
        if ((minPrice != null && minPrice < 0) || (maxPrice != null && maxPrice < 0)
                || (minPrice != null && maxPrice != null && minPrice > maxPrice)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid price range");
        }
        keyword = String.join(" ", ProductSearchKeywords.tokens(keyword));
        brand = facet(brand);
        gender = facet(gender);
        color = facet(color);
        sortBy = sortBy == null ? "LATEST" : sortBy.trim().toUpperCase(Locale.ROOT);
        if (!sortBy.equals("LATEST") && !sortBy.equals("POPULARITY")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sortBy must be LATEST or POPULARITY");
        }
    }

    private static String facet(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public PageRequest pageable() {
        return PageRequest.of(page, size);
    }

    public int fetchSize() {
        return Math.min(size + 1, RESULT_WINDOW - page * size);
    }
}
