package com.marketengine.backend.product.infrastructure.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import com.marketengine.backend.product.application.ProductListSearcher;
import com.marketengine.backend.product.domain.ProductListQuery;

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
    private ElasticsearchProductListSearcher esSearch;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE products, product_search_outbox CASCADE");
        esSearch = new ElasticsearchProductListSearcher(operations);
        var index = operations.indexOps(ProductDocument.class);
        if (index.exists()) index.delete();
    }

    @Test
    void bothStoresCoverEveryPageBoundaryAndStableTieBreaker() {
        reindexer.reindex(true);
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
                .mappings(m -> m.properties("name", p -> p.integer(i -> i))));
        assertThatThrownBy(() -> reindexer.reindex(false)).isInstanceOf(IllegalStateException.class);
        assertThat(client.exists(r -> r.index(ProductDocument.INDEX_NAME).id("1")).value()).isTrue();
        assertThat(reindexer.reindex(true).indexedCount()).isEqualTo(2);
        assertThat(esSearch.search(query(null, null, "LATEST", 0))).hasSize(2);
    }

    private ProductListQuery query(String keyword, String brand, String sort, int page) {
        return new ProductListQuery(keyword, null, brand, null, null, null, null, sort, page, 12);
    }
}
