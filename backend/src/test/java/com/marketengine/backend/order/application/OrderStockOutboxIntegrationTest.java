package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "marketengine.elasticsearch.enabled=false",
        "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.placeholders[catalogProductCount]=1",
        "spring.flyway.placeholders[catalogAsOf]=2026-11-01T00:00:00Z",
        "spring.flyway.placeholders[catalogSeed]=42"
})
class OrderStockOutboxIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private OrderService orders;
    @Autowired private ProductSearchOutbox outbox;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void ordersPublishCommittedStockAndAnIdempotentReplayDoesNotRequeueIt() {
        long productId = 1001L;
        insertProductWithTenUnits(productId);
        var request = new CreateOrderRequest(1L, productId, 2, "first-order");

        var first = orders.create(request);
        var initial = outbox.claim(10);
        assertThat(initial).singleElement().satisfies(entry -> {
            assertThat(entry.productId()).isEqualTo(productId);
            assertThat(entry.document().getStockQuantity()).isEqualTo(8);
        });
        outbox.complete(initial);

        assertThat(orders.create(request).id()).isEqualTo(first.id());
        assertThat(outbox.claim(10)).isEmpty();

        orders.create(new CreateOrderRequest(1L, productId, 1, "second-order"));
        orders.create(new CreateOrderRequest(1L, productId, 2, "third-order"));
        var latest = outbox.claim(10);
        assertThat(latest).singleElement().satisfies(entry -> {
            assertThat(entry.productId()).isEqualTo(productId);
            assertThat(entry.document().getStockQuantity()).isEqualTo(5);
        });
        outbox.complete(latest);
    }

    @Test
    @Sql(statements = "ALTER TABLE product_search_outbox ADD CONSTRAINT reject_test_sku CHECK (product_id <> 1002)")
    @Sql(statements = "ALTER TABLE product_search_outbox DROP CONSTRAINT reject_test_sku",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void outboxWriteFailureRollsBackOrderAndStockTogether() {
        long productId = 1002L;
        insertProductWithTenUnits(productId);

        assertThatThrownBy(() -> orders.create(new CreateOrderRequest(1L, productId, 2, "failed-order")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE product_id = ?", Long.class, productId))
                .isZero();
        assertThat(outbox.claim(10)).isEmpty();
    }

    private void insertProductWithTenUnits(long productId) {
        jdbc.update("INSERT INTO products (id, name, price_amount, stock_quantity) VALUES (?, 'Test SKU', 10, 10)",
                productId);
    }
}
