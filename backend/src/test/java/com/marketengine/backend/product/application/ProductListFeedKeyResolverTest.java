package com.marketengine.backend.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductListQuery;
import com.marketengine.backend.common.exception.BusinessException;

class ProductListFeedKeyResolverTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"LATEST", "latest", " LATEST "})
    void usesTheEffectiveLatestSortAndIgnoresEmptyFilters(String sort) {
        var home = resolve(null, null, null, null, null, null, null, "LATEST", 0, 12).orElseThrow();
        var equivalent = resolve(" a ", null, "", " ", null, null, null, sort, 0, 12).orElseThrow();

        assertThat(equivalent.value()).isEqualTo(home.value());
        assertThat(home.value()).isEqualTo("feed:q2?cat=&brand=&gender=&color=&min=&max=&kw=&sort=LATEST&page=0&size=12");
    }

    @Test
    void executesTheSameNormalizedQueryForEquivalentFilters() {
        var normalized = resolve("nike air", ProductCategory.SHOES, "New Balance", "MEN", "BLACK",
                1000, 5000, "POPULARITY", 2, 12).orElseThrow();
        var equivalent = resolve("  NiKe \t a   AIR  ", ProductCategory.SHOES, " New Balance ", "MEN ", " BLACK",
                1000, 5000, "popularity", 2, 12).orElseThrow();

        assertThat(equivalent.value()).isEqualTo(normalized.value());
        assertThat(equivalent.query()).isEqualTo(normalized.query());
        assertThat(equivalent.value()).isEqualTo(
                "feed:q2?cat=SHOES&brand=New+Balance&gender=MEN&color=BLACK&min=1000&max=5000&kw=nike+air&sort=POPULARITY&page=2&size=12");
    }

    @Test
    void cachesArbitraryFilterCombinationsAndSeparatesPriceBounds() {
        var category = ProductCategory.TOP;
        var categoryOnly = resolve(null, category, null, null, null, null, null, "LATEST", 1, 12).orElseThrow();
        var withMinimum = resolve(null, category, null, null, null, 1, null, "LATEST", 1, 12).orElseThrow();
        var withMaximum = resolve(null, category, null, null, null, null, 0, "LATEST", 1, 12).orElseThrow();
        var brandAndColor = resolve(null, null, "Nike", null, "BLACK", null, null, "LATEST", 1, 12).orElseThrow();

        assertThat(List.of(categoryOnly.value(), withMinimum.value(), withMaximum.value(), brandAndColor.value()))
                .doesNotHaveDuplicates();
    }

    @Test
    void preservesLiteralFilterValuesAndEncodesUserSuppliedDelimiters() {
        var withDelimiterInBrand = brandAndGender("A&gender=B", "C");
        var withDelimiterInGender = brandAndGender("A", "B&gender=C");

        assertThat(withDelimiterInBrand).contains("brand=A%26gender%3DB&gender=C").isNotEqualTo(withDelimiterInGender);
        assertThat(brandAndGender("New+Balance", "MEN")).isNotEqualTo(brandAndGender("New Balance", "MEN"));
        assertThat(brandAndGender("New  Balance", "MEN")).isNotEqualTo(brandAndGender("New Balance", "MEN"));
        assertThat(brandAndGender("Nike", "MEN")).isNotEqualTo(brandAndGender("nike", "MEN"));
    }

    private String brandAndGender(String brand, String gender) {
        return resolve(null, null, brand, gender, null, null, null, "LATEST", 0, 12).orElseThrow().value();
    }

    @ParameterizedTest
    @CsvSource({"3,12", "0,11", "2,13"})
    void bypassesTheCacheOutsideFeedPages(int page, int size) {
        assertThat(resolve(null, null, null, null, null, null, null, "LATEST", page, size)).isEmpty();
    }

    @Test
    void cursorContinuationsNeverCollideWithFirstPageCache() {
        var query = new ProductListQuery(null, null, null, null, null, null, null, "LATEST", 0, 12, "cursor");
        assertThat(ProductListFeedKeyResolver.resolve(query)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"-1,12", "0,101", "0,0", "100,100", "2147483647,12"})
    void rejectsUnsupportedPageWindows(int page, int size) {
        assertThatThrownBy(() -> resolve(null, null, null, null, null, null, null, "LATEST", page, size))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnknownSortAndReversedPriceBounds() {
        assertThatThrownBy(() -> resolve(null, null, null, null, null, null, null, "PRICE", 0, 12))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resolve(null, null, null, null, null, 5, 1, null, 0, 12))
                .isInstanceOf(BusinessException.class);
        assertThat(new ProductListQuery(null, null, null, null, null, null, null, null, 833, 12)
                .fetchSize()).isEqualTo(4);
    }

    private Optional<ProductListFeedKey> resolve(String keyword, ProductCategory category, String brand,
            String gender, String color, Integer minPrice, Integer maxPrice, String sort, int page, int size) {
        return ProductListFeedKeyResolver.resolve(new ProductListQuery(keyword, category, brand, gender, color,
                minPrice, maxPrice, sort, page, size));
    }
}
