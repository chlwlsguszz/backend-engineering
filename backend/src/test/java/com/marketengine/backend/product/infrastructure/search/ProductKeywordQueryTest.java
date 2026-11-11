package com.marketengine.backend.product.infrastructure.search;

import java.util.List;
import java.util.stream.Stream;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProductKeywordQueryTest {

    @Test
    void aShoppingQueryRequiresTheBrandColorAndItemTogether() {
        BoolQuery query = keywordQuery("나이키 검정 후드");

        assertThat(query.must()).hasSize(3);
        assertThat(query.should()).isEmpty();
        assertFacetAlternative(query.must().get(0), "brand", "Nike");
        assertFacetAlternative(query.must().get(1), "color", "BLACK");
        assertPhraseAlternative(query.must().get(2), "hoodie");
        for (Query concept : query.must()) {
            assertThat(concept.bool().minimumShouldMatch()).isEqualTo("1");
        }
    }

    @Test
    void aMultiwordBrandIsOneConceptRatherThanIndependentFuzzyWords() {
        BoolQuery query = keywordQuery("new balance 러닝화");

        assertThat(query.must()).hasSize(2);
        assertFacetAlternative(query.must().get(0), "brand", "New Balance");
        assertPhraseAlternative(query.must().get(0), "new balance");
        assertPhraseAlternative(query.must().get(1), "running shoes");
        assertThat(matches(query).toList()).allSatisfy(match -> {
            assertThat(match.operator()).isEqualTo(Operator.And);
            assertThat(match.fuzziness()).isNull();
        });
    }

    @Test
    void aliasesAlsoKeepLiteralKoreanProductNamesSearchable() {
        BoolQuery query = keywordQuery("나이키 후드");

        assertThat(matches(query).map(match -> match.query().stringValue()).toList())
                .contains("나이키", "후드");
        assertThat(matches(query).toList()).allSatisfy(match -> {
            assertThat(match.field()).isEqualTo("name");
            assertThat(match.operator()).isEqualTo(Operator.And);
            assertThat(match.fuzziness()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"hoodiee", "snekaers", "patagnia"})
    void ordinaryLongWordTyposHaveABoundedExpansion(String keyword) {
        BoolQuery query = keywordQuery(keyword);

        assertThat(query.must()).hasSize(1);
        List<MatchQuery> fuzzyMatches = matches(query)
                .filter(match -> match.fuzziness() != null).toList();
        assertThat(fuzzyMatches).singleElement().satisfies(match -> {
            assertThat(match.field()).isEqualTo("name");
            assertThat(match.operator()).isEqualTo(Operator.And);
            assertThat(match.fuzziness()).isEqualTo("1");
            assertThat(match.prefixLength()).isEqualTo(2);
            assertThat(match.maxExpansions()).isEqualTo(20);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"shirt", "short", "cap", "cat", "ME0000000", "Drop000000", "123456", "long-sleeve"})
    void shortWordsCodesAndPunctuationDoNotAcquireFuzzyMatches(String keyword) {
        BoolQuery query = keywordQuery(keyword);

        assertThat(query.must()).isNotEmpty();
        assertThat(matches(query).toList()).isNotEmpty().allSatisfy(match -> {
            assertThat(match.fuzziness()).isNull();
            assertThat(match.operator()).isEqualTo(Operator.And);
        });
    }

    @Test
    void recognizedItemNamesDoNotExpandToUnrelatedNearMatches() {
        BoolQuery query = keywordQuery("hoodie");

        assertThat(query.must()).hasSize(1);
        assertPhraseAlternative(query.must().getFirst(), "hoodie");
        assertThat(matches(query).toList()).allSatisfy(match -> assertThat(match.fuzziness()).isNull());
    }

    @Test
    void aMultiwordItemAliasRequiresTheCanonicalPhrase() {
        BoolQuery query = keywordQuery("롱슬리브");

        assertThat(query.must()).hasSize(1);
        assertPhraseAlternative(query.must().getFirst(), "long sleeve");
    }

    @Test
    void anEnglishItemPhraseCannotMatchWordsSeparatedInTheProductName() {
        BoolQuery query = keywordQuery("long sleeve");

        assertThat(query.must()).hasSize(1);
        Query concept = query.must().getFirst();
        assertThat(concept.bool().should()).hasSize(2).allSatisfy(alternative -> {
            assertThat(alternative.isMatchPhrase()).isTrue();
            assertThat(alternative.matchPhrase().field()).isEqualTo("name");
            assertThat(alternative.matchPhrase().query()).isEqualToIgnoringCase("long sleeve");
            assertThat(alternative.matchPhrase().slop()).isIn(null, 0);
        });
        assertThat(matches(query).toList()).isEmpty();
    }

    private static BoolQuery keywordQuery(String keyword) {
        BoolQuery.Builder builder = new BoolQuery.Builder();
        ProductKeywordQuery.appendTo(builder, keyword);
        return builder.build();
    }

    private static void assertFacetAlternative(Query concept, String field, String value) {
        assertThat(concept.isBool()).isTrue();
        assertThat(concept.bool().should().stream().filter(Query::isTerm).toList())
                .singleElement().satisfies(alternative -> {
                    assertThat(alternative.term().field()).isEqualTo(field);
                    assertThat(alternative.term().value().stringValue()).isEqualToIgnoringCase(value);
                    assertThat(alternative.term().caseInsensitive()).isTrue();
                });
    }

    private static void assertPhraseAlternative(Query concept, String phrase) {
        assertThat(concept.isBool()).isTrue();
        assertThat(concept.bool().should().stream().filter(Query::isMatchPhrase).toList())
                .singleElement().satisfies(alternative -> {
                    assertThat(alternative.matchPhrase().field()).isEqualTo("name");
                    assertThat(alternative.matchPhrase().query()).isEqualToIgnoringCase(phrase);
                });
    }

    private static Stream<MatchQuery> matches(BoolQuery query) {
        return query.must().stream().flatMap(ProductKeywordQueryTest::matches);
    }

    private static Stream<MatchQuery> matches(Query query) {
        if (query.isMatch()) return Stream.of(query.match());
        if (!query.isBool()) return Stream.empty();
        return Stream.concat(query.bool().must().stream(), query.bool().should().stream())
                .flatMap(ProductKeywordQueryTest::matches);
    }
}
