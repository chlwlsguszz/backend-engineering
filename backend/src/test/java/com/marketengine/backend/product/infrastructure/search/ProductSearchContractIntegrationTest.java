package com.marketengine.backend.product.infrastructure.search;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.application.ProductListSearcher;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductListQuery;
import com.marketengine.backend.product.domain.ProductSearchKeywords;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "marketengine.search.backend=postgres",
        "marketengine.elasticsearch.outbox.enabled=false",
        "marketengine.elasticsearch.reindex.workers=0",
        "marketengine.elasticsearch.reindex.batch-size=1",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.placeholders[catalogProductCount]=1",
        "spring.flyway.placeholders[catalogAsOf]=2026-11-01T00:00:00Z",
        "spring.flyway.placeholders[catalogSeed]=42"
})
class ProductSearchContractIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> es = new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200)).withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.elasticsearch.uris", () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductBulkReindexer reindexer;
    @Autowired JpaProductListSearcher pgSearch;
    @Autowired ElasticsearchOperations operations;
    @Autowired ElasticsearchClient client;
    @Autowired ProductIndexManager indexManager;
    @Autowired ObjectMapper json;
    private ElasticsearchProductListSearcher esSearch;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE products, product_search_outbox CASCADE");
        esSearch = new ElasticsearchProductListSearcher(operations, jdbc);
        var index = operations.indexOps(ProductDocument.class);
        if (index.exists()) index.delete();
    }

    @Test
    void bothStoresCoverEveryPageBoundaryAndStableTieBreaker() {
        reindexer.reindex(true);
        assertThat(operations.indexOps(ProductDocument.class).getSettings(false).flatten()
                .get("index.sort.field").toString()).contains("createdAt", "id");
        assertThat(esSearch.search(query(null, null, "LATEST", 0))).isEmpty();
        for (int count : List.of(12, 13, 25)) {
            jdbc.update("""
                    INSERT INTO products(id,name,price_amount,stock_quantity,brand,popularity_score,created_at)
                    SELECT ? * 100 + i, 'M42 Shirt', 10, 5, ?, i % 3, '2026-01-01'::timestamptz
                    FROM generate_series(1, ?) i
                    """, count, "Brand" + count, count);
        }
        assertThat(reindexer.reindex(true).indexedCount()).isEqualTo(50);
        for (int count : List.of(0, 12, 13, 25)) {
            for (var sort : List.of("LATEST", "POPULARITY")) {
                String brand = "Brand" + count;
                List<Long> sqlIds = jdbc.queryForList("SELECT id FROM products WHERE brand=? ORDER BY "
                        + ("POPULARITY".equals(sort) ? "popularity_score DESC, " : "created_at DESC, ")
                        + "id DESC", Long.class, brand);
                for (ProductListSearcher store : List.of(pgSearch, esSearch)) {
                    List<Long> ids = new ArrayList<>();
                    for (int page = 0; page < Math.max(1, (count + 11) / 12); page++) {
                        var slice = store.search(query(" m42  SHIRT ", brand, sort, page));
                        assertThat(slice.hasNext()).isEqualTo((page + 1) * 12 < count);
                        ids.addAll(slice.getContent().stream().map(ProductSummaryResponse::id).toList());
                    }
                    assertThat(ids).containsExactlyElementsOf(sqlIds);
                }
            }
        }
    }

    @Test
    void percentAndUnderscoreAreLiteralInPostgresSubstringSearch() {
        jdbc.update("""
                INSERT INTO products(id,name,price_amount,stock_quantity) VALUES
                (1,'M42_50% Shirt',10,1),(2,'M42X501 Shirt',10,1)
                """);
        assertThat(pgSearch.search(query("42_50%", null, "LATEST", 0)))
                .extracting(ProductSummaryResponse::id).containsExactly(1L);
        assertThat(pgSearch.search(query(" x ", null, "LATEST", 0))).hasSize(2);
    }

    @Test
    void canRecreateAndFullyVerifyAfterAPartiallyWrittenReindex() throws Exception {
        jdbc.update("""
                INSERT INTO products(id,name,price_amount,stock_quantity) VALUES
                (1,'123',123.4567,9),(2,'Shirt',10,1)
                """);
        client.indices().create(c -> c.index(ProductDocument.INDEX_NAME)
                .settings(s -> s.refreshInterval(t -> t.time("5s")))
                .mappings(m -> m.properties("name", p -> p.integer(i -> i))));
        assertThatThrownBy(() -> reindexer.reindex(false)).isInstanceOf(IllegalStateException.class);
        assertThat(refreshInterval()).isEqualTo("5s");
        assertThat(client.exists(r -> r.index(ProductDocument.INDEX_NAME).id("1")).value()).isTrue();
        assertThat(reindexer.reindex(true).indexedCount()).isEqualTo(2);
        assertThat(esSearch.search(query(null, null, "LATEST", 0))).hasSize(2);
    }

    @Test
    void restoresInheritedRefreshSettingAfterFailure() throws Exception {
        client.indices().create(c -> c.index(ProductDocument.INDEX_NAME));
        assertThat(refreshInterval()).isNull();
        assertThatThrownBy(() -> {
            try (var refresh = indexManager.suspendRefresh()) {
                assertThat(refreshInterval()).isEqualTo("-1");
                throw new IllegalStateException("interrupted bulk");
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("interrupted bulk");
        assertThat(refreshInterval()).isNull();
    }

    @Test
    void firstReindexCreatesAMappedIndexWithoutRecreateFlag() {
        assertThat(reindexer.reindex(false).indexedCount()).isZero();
        assertThat(operations.indexOps(ProductDocument.class).getMapping()).containsKey("properties");
        assertThat(refreshInterval()).isNotEqualTo("-1");
    }

    @Test
    void oldDocumentsReadCurrentAvailabilityInOneBatchUntilReindexed() throws Exception {
        jdbc.update("""
                INSERT INTO products(id,name,price_amount,stock_quantity,status) VALUES
                (1,'Legacy One',10,9,'INACTIVE'),(2,'Legacy Two',10,0,'OUT_OF_STOCK'),
                (3,'Legacy Three',10,3,'ACTIVE')
                """);
        indexManager.create();
        for (long id : List.of(1L, 2L, 3L)) {
            long productId = id;
            client.index(request -> request.index(ProductDocument.INDEX_NAME).id(Long.toString(productId))
                    .document(Map.of("id", productId, "name", "Legacy", "priceAmount", 10,
                            "stockQuantity", 99, "category", "TOP", "popularityScore", 0,
                            "createdAt", "2026-01-01T00:00:00.000Z")));
        }
        operations.indexOps(ProductDocument.class).refresh();
        var results = esSearch.search(query(null, null, "LATEST", 0)).getContent();
        assertThat(results).extracting(ProductSummaryResponse::id).containsExactly(3L, 2L, 1L);
        assertThat(results).extracting(ProductSummaryResponse::available).containsExactly(true, false, false);
    }

    @Test
    void bothStoresContinueWithKeysetCursorsWithoutSkippingTies() {
        jdbc.update("""
                INSERT INTO products(id,name,price_amount,stock_quantity,popularity_score,created_at)
                SELECT i, 'Cursor Shirt', 10, 3, i % 3,
                       '2026-01-01'::timestamptz + (26-i) * interval '1 microsecond'
                FROM generate_series(1,25) i
                """);
        reindexer.reindex(true);
        for (ProductListSearcher store : List.of(pgSearch, esSearch)) {
            for (String sort : List.of("LATEST", "POPULARITY")) {
                var all = new ProductListQuery("cursor", null, null, null, null, null, null, sort, 0, 100);
                List<Long> expected = store.search(all).getContent().stream().map(ProductSummaryResponse::id).toList();
                List<Long> actual = new ArrayList<>();
                String next = null;
                int pages = 0;
                do {
                    var query = new ProductListQuery("cursor", null, null, null, null, null, null, sort, 0, 7, next);
                    var result = ProductPageResponse.from(store.search(query), query, store.cursorScope());
                    actual.addAll(result.items().stream().map(ProductSummaryResponse::id).toList());
                    assertThat(result.hasNext()).isEqualTo(result.nextCursor() != null);
                    next = result.nextCursor();
                    assertThat(++pages).isLessThanOrEqualTo(4);
                } while (next != null);
                assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
            }
        }
    }

    @Test
    void judgedShoppingQueriesImproveCoverageWithoutIntroducingUnrelatedProducts() throws IOException {
        QualityFixture fixture = loadQualityCatalogue();
        assertThat(fixture.queries()).hasSizeGreaterThanOrEqualTo(50);
        QualityMetrics baseline = new QualityMetrics();
        QualityMetrics improved = new QualityMetrics();
        List<String> mismatches = new ArrayList<>();

        for (QualityCase scenario : fixture.queries()) {
            List<Long> oldIds = originalKeywordResults(scenario.keyword());
            var query = new ProductListQuery(scenario.keyword(), null, null, null, null,
                    null, null, "LATEST", 0, 100);
            List<Long> newIds = resultIds(query);
            baseline.record(scenario.expectedIds(), oldIds);
            improved.record(scenario.expectedIds(), newIds);
            if (!new HashSet<>(newIds).equals(new HashSet<>(scenario.expectedIds()))
                    || new HashSet<>(newIds).size() != newIds.size()) {
                mismatches.add("keyword='" + scenario.keyword() + "' expected=" + scenario.expectedIds()
                        + " actual=" + newIds);
            }
        }

        // These are fixture measurements produced only when the real ES test is executed.
        // They evaluate retrieval, not relevance ranking or production search traffic.
        baseline.print("original-name-match");
        improved.print("catalogue-aliases-and-bounded-typos");
        assertThat(mismatches).as("Judged search cases").isEmpty();
        assertThat(improved.coveredPositiveQueries).isGreaterThan(baseline.coveredPositiveQueries);
        assertThat(improved.exactQueries).isGreaterThan(baseline.exactQueries);

        assertThat(pgSearch.search(query("나이키 후드", null, "LATEST", 0))).isEmpty();
        assertThat(pgSearch.search(query("hoodiee", null, "LATEST", 0))).isEmpty();
    }

    @Test
    void qualityMatchingStillHonorsAllExplicitFiltersAndDeletedDocuments() throws IOException {
        loadQualityCatalogue();
        var filtered = new ProductListQuery("후드", ProductCategory.TOP, "Nike", "MEN", "BLACK",
                40000, 55000, "LATEST", 0, 12);
        assertThat(resultIds(filtered)).containsExactly(1L);
        assertThat(resultIds(new ProductListQuery("후드", ProductCategory.TOP, "Adidas", "MEN", "BLACK",
                40000, 55000, "LATEST", 0, 12))).isEmpty();
        assertThat(resultIds(new ProductListQuery("나이키", ProductCategory.SHOES, null, null, null,
                null, null, "LATEST", 0, 12))).isEmpty();
        assertThat(resultIds(new ProductListQuery("hoodiee", null, "Nike", "WOMEN", "WHITE",
                60000, 60000, "POPULARITY", 0, 12))).containsExactly(2L);

        client.index(request -> request.index(ProductDocument.INDEX_NAME).id("1")
                .document(Map.of("id", 1L, "name", "Nike MEN Hoodie ME0000001 Black", "outboxDeleted", true)));
        operations.indexOps(ProductDocument.class).refresh();
        assertThat(resultIds(query("나이키 후드", null, "LATEST", 0)))
                .containsExactlyInAnyOrder(2L, 30L);
        assertThat(resultIds(query("hoodiee", null, "LATEST", 0))).containsExactly(2L);
    }

    @Test
    void qualitySearchKeepsOffsetAndCursorResultsCompleteForBothSorts() throws IOException {
        loadQualityCatalogue();
        for (String keyword : List.of("나이키", "hoodiee")) {
            int pageSize = "나이키".equals(keyword) ? 3 : 1;
            String predicate = "나이키".equals(keyword) ? "brand='Nike'" : "name LIKE '% Hoodie %'";
            for (String sort : List.of("LATEST", "POPULARITY")) {
                assertQualityPages(keyword, predicate, sort, pageSize);
            }
        }
    }

    private void assertQualityPages(String keyword, String predicate, String sort, int pageSize) {
        List<Long> expected = jdbc.queryForList("SELECT id FROM products WHERE " + predicate + " ORDER BY "
                + ("POPULARITY".equals(sort) ? "popularity_score DESC, " : "created_at DESC, ")
                + "id DESC", Long.class);
        List<Long> offsetIds = new ArrayList<>();
        int expectedPages = (expected.size() + pageSize - 1) / pageSize;
        for (int page = 0; page < expectedPages; page++) {
            var query = new ProductListQuery(keyword, null, null, null, null,
                    null, null, sort, page, pageSize);
            var result = esSearch.search(query);
            offsetIds.addAll(result.getContent().stream().map(ProductSummaryResponse::id).toList());
            assertThat(result.hasNext()).isEqualTo((page + 1) * pageSize < expected.size());
        }
        assertThat(offsetIds).as("Offset: %s / %s", keyword, sort)
                .containsExactlyElementsOf(expected).doesNotHaveDuplicates();

        List<Long> cursorIds = new ArrayList<>();
        String next = null;
        int pages = 0;
        do {
            var query = new ProductListQuery(keyword, null, null, null, null,
                    null, null, sort, 0, pageSize, next);
            var result = ProductPageResponse.from(esSearch.search(query), query, esSearch.cursorScope());
            cursorIds.addAll(result.items().stream().map(ProductSummaryResponse::id).toList());
            assertThat(result.hasNext()).isEqualTo(result.nextCursor() != null);
            next = result.nextCursor();
            assertThat(++pages).isLessThanOrEqualTo(expectedPages);
        } while (next != null);
        assertThat(cursorIds).as("Cursor: %s / %s", keyword, sort)
                .containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    private QualityFixture loadQualityCatalogue() throws IOException {
        QualityFixture fixture;
        try (var input = getClass().getResourceAsStream("/search/product-search-quality.json")) {
            if (input == null) throw new IllegalStateException("Missing judged search fixture");
            fixture = json.readValue(input, QualityFixture.class);
        }
        for (QualityProduct product : fixture.products()) {
            jdbc.update("""
                    INSERT INTO products(id,name,price_amount,stock_quantity,category,brand,color,gender,
                                         popularity_score,created_at)
                    VALUES (?,?,?,10,?,?,?,?,?,'2026-01-01T00:00:00Z')
                    """, product.id(), product.name(), product.price(), product.category(), product.brand(),
                    product.color(), product.gender(), product.popularity());
        }
        assertThat(reindexer.reindex(true).indexedCount()).isEqualTo(fixture.products().size());
        return fixture;
    }

    private List<Long> resultIds(ProductListQuery query) {
        return esSearch.search(query).getContent().stream().map(ProductSummaryResponse::id).toList();
    }

    private List<Long> originalKeywordResults(String keyword) {
        var original = ProductDocument.visibleProducts();
        for (String token : ProductSearchKeywords.tokens(keyword)) {
            original.must(must -> must.match(match -> match.field("name").query(token)));
        }
        var query = NativeQuery.builder().withQuery(q -> q.bool(original.build()))
                .withPageable(PageRequest.of(0, 100)).withTrackTotalHits(false).build();
        return operations.search(query, ProductDocument.class).getSearchHits().stream()
                .map(hit -> hit.getContent().getId()).toList();
    }

    private record QualityFixture(String description, List<QualityProduct> products, List<QualityCase> queries) {}
    private record QualityProduct(long id, String name, String brand, String category, String color,
                                  String gender, int price, int popularity) {}
    private record QualityCase(String keyword, List<Long> expectedIds) {}

    private static final class QualityMetrics {
        private int queries;
        private int positiveQueries;
        private int coveredPositiveQueries;
        private int exactQueries;
        private long relevantRetrieved;
        private long retrieved;

        private void record(List<Long> expected, List<Long> actual) {
            queries++;
            var relevant = new HashSet<>(expected);
            long matching = actual.stream().filter(relevant::contains).count();
            if (!expected.isEmpty()) {
                positiveQueries++;
                if (matching > 0) coveredPositiveQueries++;
            }
            if (relevant.equals(new HashSet<>(actual)) && new HashSet<>(actual).size() == actual.size()) exactQueries++;
            relevantRetrieved += matching;
            retrieved += actual.size();
        }

        private void print(String label) {
            double precision = retrieved == 0 ? 0 : 100.0 * relevantRetrieved / retrieved;
            System.out.printf(Locale.ROOT,
                    "Search quality fixture [%s]: positive coverage=%d/%d, exact cases=%d/%d, precision=%.2f%% (%d/%d)%n",
                    label, coveredPositiveQueries, positiveQueries, exactQueries, queries,
                    precision, relevantRetrieved, retrieved);
        }
    }

    private String refreshInterval() {
        Object value = operations.indexOps(ProductDocument.class).getSettings(false).flatten()
                .get("index.refresh_interval");
        return value == null ? null : value.toString();
    }

    private ProductListQuery query(String keyword, String brand, String sort, int page) {
        return new ProductListQuery(keyword, null, brand, null, null, null, null, sort, page, 12);
    }
}
