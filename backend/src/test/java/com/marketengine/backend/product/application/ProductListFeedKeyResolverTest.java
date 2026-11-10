package com.marketengine.backend.product.application;

import static com.marketengine.backend.product.application.ProductListFeedKeyResolver.resolve;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.marketengine.backend.product.domain.ProductCategory;

class ProductListFeedKeyResolverTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"LATEST", "latest", "PRICE", " POPULARITY "})
    void usesTheEffectiveLatestSortAndIgnoresEmptyFilters(String sort) {
        var home = resolve(null, null, null, null, null, null, null, "LATEST", 0, 12).orElseThrow();
        var equivalent = resolve(" a ", null, "", " ", null, null, null, sort, 0, 12).orElseThrow();

        assertThat(equivalent.value()).isEqualTo(home.value());
        assertThat(home.value()).isEqualTo("feed:q1?cat=&brand=&gender=&color=&min=&max=&kw=&sort=LATEST&page=0&size=12");
    }

    @Test
    void sharesTheKeyForEquivalentFiltersAndRetainsOriginalSearchParameters() {
        var normalized = resolve("nike air", ProductCategory.SHOES, "New Balance", "MEN", "BLACK",
                1000, 5000, "POPULARITY", 2, 12).orElseThrow();
        var equivalent = resolve("  NiKe \t a   AIR  ", ProductCategory.SHOES, " New Balance ", "MEN ", " BLACK",
                1000, 5000, "popularity", 2, 12).orElseThrow();

        assertThat(equivalent.value()).isEqualTo(normalized.value());
        assertThat(equivalent.query().brand()).isEqualTo(" New Balance ");
        assertThat(equivalent.value()).isEqualTo(
                "feed:q1?cat=SHOES&brand=New+Balance&gender=MEN&color=BLACK&min=1000&max=5000&kw=nike+air&sort=POPULARITY&page=2&size=12");
    }

    @Test
    void cachesArbitraryFilterCombinationsAndSeparatesPriceBounds() {
        var category = ProductCategory.TOP;
        var categoryOnly = resolve(null, category, null, null, null, null, null, "LATEST", 1, 12).orElseThrow();
        var withMinimum = resolve(null, category, null, null, null, -1, null, "LATEST", 1, 12).orElseThrow();
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
    @CsvSource({"-1,12", "3,12", "0,11", "2,13"})
    void bypassesTheCacheOutsideFeedPages(int page, int size) {
        assertThat(resolve(null, null, null, null, null, null, null, "LATEST", page, size)).isEmpty();
    }
}
