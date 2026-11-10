package com.marketengine.backend.product.infrastructure.search;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import com.marketengine.backend.product.domain.ProductListQuery;
import com.marketengine.backend.product.domain.ProductSearchKeywords;

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

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public Slice<ProductSummaryResponse> search(ProductListQuery criteria) {
        Pageable pageable = criteria.pageable();
        // Spring Data obtains ES "from" from Pageable and "size" from getRequestSize().
        // Increasing the page size itself shifts the start of every later page.
        NativeQuery sliceQuery = new NativeQuery(NativeQuery.builder()
                .withQuery(buildQuery(criteria)).withSort(buildSort(criteria.sortBy()))
                .withPageable(pageable).withTrackTotalHits(false)) {
            @Override
            public Integer getRequestSize() {
                return criteria.fetchSize();
            }
        };
        SearchHits<ProductDocument> hits = elasticsearchOperations.search(sliceQuery, ProductDocument.class);

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

    private static Query buildQuery(ProductListQuery search) {
        BoolQuery.Builder bool = ProductDocument.visibleProducts();

        appendKeywordMust(bool, search.keyword());

        if (search.category() != null) {
            bool.filter(filterTerm("category", search.category().name()));
        }
        if (search.brand() != null) {
            bool.filter(filterTerm("brand", search.brand()));
        }
        if (search.gender() != null) {
            bool.filter(filterTerm("gender", search.gender()));
        }
        if (search.color() != null) {
            bool.filter(filterTerm("color", search.color()));
        }
        if (search.minPrice() != null) {
            bool.filter(filterRangeGte("priceAmount", search.minPrice().doubleValue()));
        }
        if (search.maxPrice() != null) {
            bool.filter(filterRangeLte("priceAmount", search.maxPrice().doubleValue()));
        }

        return Query.of(query -> query.bool(bool.build()));
    }

    private static void appendKeywordMust(BoolQuery.Builder bool, String keyword) {
        for (String token : ProductSearchKeywords.tokens(keyword)) {
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
