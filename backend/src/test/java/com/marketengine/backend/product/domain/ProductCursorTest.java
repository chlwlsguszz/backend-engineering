package com.marketengine.backend.product.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductCursorTest {
    private final ProductSummaryResponse item = new ProductSummaryResponse(19L, "Air Shirt", BigDecimal.TEN,
            true, ProductCategory.TOP, "Nike", "BLACK", "UNISEX", 42,
            OffsetDateTime.parse("2026-01-01T12:00:00.123456+09:00"));

    @Test
    void normalizedQueryContinuesWithPostgresMicrosecondPrecision() {
        var initial = query(" NiKe  AIR ", " latest ", 12, null);
        String cursor = ProductCursor.encode(initial, item, ProductCursor.POSTGRES);
        var next = query("nike air", "LATEST", 12, cursor);
        ProductCursor decoded = ProductCursor.decode(next, ProductCursor.POSTGRES);
        assertThat(decoded.id()).isEqualTo(19);
        assertThat(decoded.createdAt().toInstant()).isEqualTo(item.createdAt().toInstant());
        assertThat(decoded.elasticsearchSortValues()).containsExactly(item.createdAt().toInstant().toEpochMilli(), 19L);
        assertThat(next.fetchSize()).isEqualTo(13);
    }

    @Test
    void popularityCursorKeepsTheTieBreakingProductId() {
        var query = query(null, "POPULARITY", 12, null);
        String cursor = ProductCursor.encode(query, item, ProductCursor.ELASTICSEARCH);
        var next = ProductCursor.decode(query(null, "POPULARITY", 12, cursor), ProductCursor.ELASTICSEARCH);
        assertThat(next.popularityScore()).isEqualTo(42);
        assertThat(next.elasticsearchSortValues()).containsExactly(42L, 19L);
    }

    @Test
    void rejectsChangedFiltersPageSizeSortAndBackend() {
        String cursor = ProductCursor.encode(query("nike air", "LATEST", 12, null), item, ProductCursor.POSTGRES);
        for (var changed : new ProductListQuery[] {
                query("other", "LATEST", 12, cursor),
                query("nike air", "LATEST", 24, cursor),
                query("nike air", "POPULARITY", 12, cursor)}) {
            assertThatThrownBy(() -> ProductCursor.decode(changed, ProductCursor.POSTGRES))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> ProductCursor.decode(query("nike air", "LATEST", 12, cursor),
                ProductCursor.ELASTICSEARCH)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMalformedCursorAndInvalidSeekPosition() {
        assertThatThrownBy(() -> ProductCursor.decode(query(null, "LATEST", 12, "not_base64!"),
                ProductCursor.POSTGRES)).isInstanceOf(BusinessException.class);
        String valid = ProductCursor.encode(query(null, "LATEST", 12, null), item, ProductCursor.POSTGRES);
        String raw = new String(Base64.getUrlDecoder().decode(valid), StandardCharsets.UTF_8);
        String[] fields = raw.split("\\|", -1);
        fields[2] = "+100000000-01-01T00:00:00Z";
        String oversizedDate = Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.join("|", fields).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ProductCursor.decode(query(null, "LATEST", 12, oversizedDate),
                ProductCursor.POSTGRES)).isInstanceOf(BusinessException.class);
        String negativeId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                raw.substring(0, raw.lastIndexOf('|') + 1).concat("-1").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ProductCursor.decode(query(null, "LATEST", 12, negativeId),
                ProductCursor.POSTGRES)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query(null, "LATEST", 12, "x".repeat(513)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ProductListQuery(null, null, null, null, null, null, null,
                "LATEST", 1, 12, valid)).isInstanceOf(BusinessException.class);
    }

    private ProductListQuery query(String keyword, String sort, int size, String after) {
        return new ProductListQuery(keyword, null, null, null, null, null, null, sort, 0, size, after);
    }
}
