package com.marketengine.backend.product.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.marketengine.backend.product.domain.ProductCategory;

class ProductListFeedKeyResolverTest {

    @Test
    void resolve_homeLatestFeed() {
        var key = ProductListFeedKeyResolver.resolve(
                null, null, null, null, null, null, null, "LATEST", 0, 12
        );

        assertThat(key).isPresent();
        assertThat(key.get().value()).isEqualTo("feed:home:latest:0");
    }

    @Test
    void resolve_categoryPopularityFeed() {
        var key = ProductListFeedKeyResolver.resolve(
                null, ProductCategory.TOP, null, null, null, null, null, "POPULARITY", 1, 12
        );

        assertThat(key).isPresent();
        assertThat(key.get().value()).isEqualTo("feed:category:TOP:popularity:1");
    }

    @Test
    void resolve_keywordFeed() {
        var key = ProductListFeedKeyResolver.resolve(
                "nike", null, null, null, null, null, null, "LATEST", 2, 12
        );

        assertThat(key).isPresent();
        assertThat(key.get().value()).isEqualTo("feed:keyword:nike:latest:2");
    }

    @Test
    void resolve_priceBandFeed() {
        var key = ProductListFeedKeyResolver.resolve(
                null, null, null, null, null, 1000, 5000, "LATEST", 0, 12
        );

        assertThat(key).isPresent();
        assertThat(key.get().value()).isEqualTo("feed:price:1000:5000:latest:0");
    }

    @Test
    void resolve_categoryBrandFeed() {
        var key = ProductListFeedKeyResolver.resolve(
                null, ProductCategory.SHOES, "NIKE", null, null, null, null, "LATEST", 0, 12
        );

        assertThat(key).isPresent();
        assertThat(key.get().value()).isEqualTo("feed:category-brand:SHOES:NIKE:latest:0");
    }

    @Test
    void resolve_fullFacetFeed() {
        var key = ProductListFeedKeyResolver.resolve(
                "boot",
                ProductCategory.SHOES,
                "NIKE",
                "MEN",
                "BLACK",
                1000,
                5000,
                "POPULARITY",
                1,
                12
        );

        assertThat(key).isPresent();
        assertThat(key.get().value()).isEqualTo(
                "feed:full:boot:SHOES:NIKE:MEN:BLACK:1000:5000:popularity:1"
        );
    }

    @ParameterizedTest
    @CsvSource({
            "3, 12, LATEST",
            "0, 20, LATEST",
            "0, 12, PRICE",
    })
    void resolve_returnsEmptyForNonCacheableRequest(int page, int size, String sortBy) {
        var key = ProductListFeedKeyResolver.resolve(
                null, null, null, null, null, null, null, sortBy, page, size
        );

        assertThat(key).isEmpty();
    }

    @Test
    void resolve_returnsEmptyForUnsupportedFilterCombination() {
        var key = ProductListFeedKeyResolver.resolve(
                null, ProductCategory.TOP, null, "MEN", null, null, null, "LATEST", 0, 12
        );

        assertThat(key).isEmpty();
    }
}
