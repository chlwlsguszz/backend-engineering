package com.marketengine.backend.product.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.elasticsearch.DataElasticsearchTest;
import com.marketengine.backend.product.domain.ProductListQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox.Entry;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

@Testcontainers(disabledWithoutDocker = true)
@DataElasticsearchTest(properties = "spring.data.elasticsearch.repositories.enabled=false")
class ProductOutboxPublisherIntegrationTest {

    @Container
    static final GenericContainer<?> es = new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired private ElasticsearchClient client;
    @Autowired private ElasticsearchOperations operations;
    private ProductOutboxPublisher publisher;

    @DynamicPropertySource
    static void configureElasticsearch(DynamicPropertyRegistry properties) {
        properties.add("spring.elasticsearch.uris", () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
    }

    @BeforeEach
    void createProductIndex() {
        var index = operations.indexOps(ProductDocument.class);
        if (index.exists()) {
            index.delete();
        }
        index.createWithMapping();
        publisher = new ProductOutboxPublisher(client, operations.getElasticsearchConverter());
    }

    @Test
    void ignoresReplayedAndOlderRevisionsAndKeepsLegacyProductsSearchable() {
        operations.save(document(1, "Legacy"));
        operations.save(document(2, "Original"));
        Entry create = new Entry(3, 1, UUID.randomUUID(), document(3, "New"));
        Entry update = new Entry(2, 2, UUID.randomUUID(), document(2, "Changed"));
        Entry delete = new Entry(2, 3, UUID.randomUUID(), null);

        assertThat(publisher.publish(List.of(create, update))).containsExactly(create, update);
        assertThat(operations.get("2", ProductDocument.class).getName()).isEqualTo("Changed");
        assertThat(publisher.publish(List.of(delete))).containsExactly(delete);
        assertThat(publisher.publish(List.of(update, create))).containsExactly(update, create);
        operations.indexOps(ProductDocument.class).refresh();

        var searcher = new ElasticsearchProductListSearcher(operations);
        var products = searcher.search(new ProductListQuery(null, null, null, null, null, null, null, "LATEST", 0, 12));
        assertThat(products.getContent()).extracting(ProductSummaryResponse::name)
                .containsExactlyInAnyOrder("Legacy", "New");
    }

    @Test
    void returnsOnlySuccessfulItemsWhenOneDocumentFailsMapping() {
        var converter = spy(operations.getElasticsearchConverter());
        Entry invalid = new Entry(1, 1, UUID.randomUUID(), document(1, "Invalid"));
        doReturn(Document.from(Map.of("stockQuantity", "invalid"))).when(converter).mapObject(invalid.document());
        var publisherWithInvalidDocument = new ProductOutboxPublisher(client, converter);
        Entry valid = new Entry(2, 2, UUID.randomUUID(), document(2, "Valid"));

        assertThat(publisherWithInvalidDocument.publish(List.of(invalid, valid))).containsExactly(valid);
        assertThat(operations.get("2", ProductDocument.class).getName()).isEqualTo("Valid");
    }

    private static ProductDocument document(long id, String name) {
        return new ProductDocument(id, name, BigDecimal.TEN, 3, "TOP", "Brand", "BLACK", "UNISEX",
                1, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }
}
