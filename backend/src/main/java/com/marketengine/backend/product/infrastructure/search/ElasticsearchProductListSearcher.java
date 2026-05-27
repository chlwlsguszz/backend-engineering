package com.marketengine.backend.product.infrastructure.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;

import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.application.ProductListSearcher;
import com.marketengine.backend.product.domain.ProductCategory;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;

/**
 * Step 5: list/search via Elasticsearch (match AND on name tokens, keyword filters, slice paging).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "marketengine.search", name = "backend", havingValue = "elasticsearch", matchIfMissing = true)
public class ElasticsearchProductListSearcher implements ProductListSearcher {

    private static final int KEYWORD_MIN_LENGTH = 2;

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public Slice<ProductSummaryResponse> search(
            String keyword,
            ProductCategory category,
            String brand,
            String gender,
            String color,
            Integer minPrice,
            Integer maxPrice,
            String sortBy,
            Pageable pageable
    ) {
        int fetchSize = pageable.getPageSize() + 1;
        Pageable fetchPageable = PageRequest.of(pageable.getPageNumber(), fetchSize, pageable.getSort());

        NativeQuery query = NativeQuery.builder()
                .withQuery(buildQuery(keyword, category, brand, gender, color, minPrice, maxPrice))
                .withSort(buildSort(sortBy))
                .withPageable(fetchPageable)
                .build();

        SearchHits<ProductDocument> hits = elasticsearchOperations.search(query, ProductDocument.class);

        List<ProductSummaryResponse> items = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(ElasticsearchProductListSearcher::toSummary)
                .toList();

        boolean hasNext = items.size() > pageable.getPageSize();
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, pageable.getPageSize()));
        }

        return new SliceImpl<>(items, pageable, hasNext);
    }

    private static Query buildQuery(
            String keyword,
            ProductCategory category,
            String brand,
            String gender,
            String color,
            Integer minPrice,
            Integer maxPrice
    ) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        appendKeywordMust(bool, keyword);

        if (category != null) {
            bool.filter(filterTerm("category", category.name()));
        }
        if (hasText(brand)) {
            bool.filter(filterTerm("brand", brand.trim()));
        }
        if (hasText(gender)) {
            bool.filter(filterTerm("gender", gender.trim()));
        }
        if (hasText(color)) {
            bool.filter(filterTerm("color", color.trim()));
        }
        if (minPrice != null) {
            bool.filter(filterRangeGte("priceAmount", minPrice.doubleValue()));
        }
        if (maxPrice != null) {
            bool.filter(filterRangeLte("priceAmount", maxPrice.doubleValue()));
        }

        return Query.of(query -> query.bool(bool.build()));
    }

    private static void appendKeywordMust(BoolQuery.Builder bool, String keyword) {
        if (!hasText(keyword)) {
            return;
        }
        String[] tokens = keyword.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (String token : tokens) {
            if (token.length() < KEYWORD_MIN_LENGTH) {
                continue;
            }
            bool.must(must -> must.match(match -> match.field("name").query(token)));
        }
    }

    private static Query filterTerm(String field, String value) {
        return Query.of(query -> query.term(term -> term.field(field).value(value)));
    }

    private static Query filterRangeGte(String field, double min) {
        return Query.of(query -> query.range(range -> range.number(number -> number.field(field).gte(min))));
    }

    private static Query filterRangeLte(String field, double max) {
        return Query.of(query -> query.range(range -> range.number(number -> number.field(field).lte(max))));
    }

    private static List<SortOptions> buildSort(String sortBy) {
        if ("POPULARITY".equalsIgnoreCase(sortBy)) {
            return List.of(
                    SortOptions.of(sort -> sort.field(field -> field.field("popularityScore").order(SortOrder.Desc))),
                    SortOptions.of(sort -> sort.field(field -> field.field("id").order(SortOrder.Desc)))
            );
        }
        return List.of(
                SortOptions.of(sort -> sort.field(field -> field.field("createdAt").order(SortOrder.Desc))),
                SortOptions.of(sort -> sort.field(field -> field.field("id").order(SortOrder.Desc)))
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static ProductSummaryResponse toSummary(ProductDocument document) {
        return new ProductSummaryResponse(
                document.getId(),
                document.getName(),
                document.getPriceAmount(),
                document.getStockQuantity(),
                ProductCategory.valueOf(document.getCategory()),
                document.getBrand(),
                document.getColor(),
                document.getGender(),
                document.getPopularityScore(),
                document.getCreatedAt()
        );
    }
}
