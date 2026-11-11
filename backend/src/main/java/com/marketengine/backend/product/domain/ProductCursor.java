package com.marketengine.backend.product.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;

/** A bounded public seek position, bound to the normalized query and its read store. */
public record ProductCursor(long id, OffsetDateTime createdAt, Integer popularityScore) {
    public static final int MAX_LENGTH = 512;
    public static final String POSTGRES = "postgres";
    public static final String ELASTICSEARCH = "elasticsearch";

    public static String encode(ProductListQuery query, ProductSummaryResponse last, String scope) {
        String position = "POPULARITY".equals(query.sortBy()) ? Integer.toString(last.popularityScore())
                : last.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString();
        String body = "1|" + fingerprint(query, scope) + "|" + position + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }

    public static ProductCursor decode(ProductListQuery query, String scope) {
        if (query.after() == null) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(query.after()), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 4 || !"1".equals(parts[0]) || !fingerprint(query, scope).equals(parts[1])) {
                throw new IllegalArgumentException("Cursor does not match the query or search backend");
            }
            long id = Long.parseLong(parts[3]);
            if (id <= 0) throw new IllegalArgumentException("Invalid cursor product ID");
            if ("POPULARITY".equals(query.sortBy())) {
                int score = Integer.parseInt(parts[2]);
                if (score < 0) throw new IllegalArgumentException("Invalid cursor popularity score");
                return new ProductCursor(id, null, score);
            }
            OffsetDateTime date = OffsetDateTime.parse(parts[2]);
            if (date.getYear() < 1 || date.getYear() > 9999) {
                throw new IllegalArgumentException("Cursor date is outside the supported catalogue range");
            }
            // Both stores must be able to represent the timestamp in their date sort.
            date.toInstant().toEpochMilli();
            return new ProductCursor(id, date, null);
        } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "Invalid cursor. Keep the original filters, sort, size and search backend.");
        }
    }

    public List<Object> elasticsearchSortValues() {
        return List.of(createdAt == null ? popularityScore.longValue() : createdAt.toInstant().toEpochMilli(), id);
    }

    private static String fingerprint(ProductListQuery query, String scope) {
        if (!POSTGRES.equals(scope) && !ELASTICSEARCH.equals(scope)) {
            throw new IllegalArgumentException("Unknown cursor search backend");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Length prefixes make filter delimiters and literal null-like values unambiguous.
            Object[] values = {scope, query.keyword(), query.category(), query.brand(), query.gender(), query.color(),
                    query.minPrice(), query.maxPrice(), query.sortBy(), query.size()};
            for (Object value : values) {
                byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
