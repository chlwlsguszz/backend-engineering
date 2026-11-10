package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

import io.micrometer.core.instrument.MeterRegistry;

@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureObservability
@SpringBootTest(properties = {
        "marketengine.elasticsearch.enabled=false",
        "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.pool-name=order-stock-outbox",
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
    @Autowired private TransactionTemplate transaction;

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
    void concurrentDuplicateOrdersForTheLastUnitReturnTheSameOrder() throws Exception {
        long productId = 1003L;
        jdbc.update("INSERT INTO products (id, name, price_amount, stock_quantity) VALUES (?, 'Last unit', 10, 1)",
                productId);
        var request = new CreateOrderRequest(1L, productId, 1, "duplicate-last-unit");

        try (var callers = Executors.newFixedThreadPool(2)) {
            var replies = transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM products WHERE id = ? FOR UPDATE", Long.class, productId);
                var first = callers.submit(() -> createOrderOutcome(request));
                var second = callers.submit(() -> createOrderOutcome(request));
                // Release the gate only after both requests have missed the key and reached the stock lock.
                await().alias("both orders waiting for the product row").pollInterval(Duration.ofMillis(25))
                        .atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                                SELECT count(DISTINCT pid) FROM pg_locks
                                WHERE relation = 'products'::regclass AND mode = 'RowShareLock' AND granted
                                  AND cardinality(pg_blocking_pids(pid)) > 0
                                """, Integer.class) == 2);
                return List.of(first, second);
            });
            var outcomes = List.of(replies.get(0).get(10, TimeUnit.SECONDS), replies.get(1).get(10, TimeUnit.SECONDS));
            var orderIds = jdbc.queryForList("SELECT id FROM orders WHERE product_id = ?", Long.class, productId);
            var stock = jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId);
            var pending = outbox.claim(10);
            try {
                assertAll(
                        () -> assertThat(orderIds).hasSize(1),
                        () -> assertThat(stock).isZero(),
                        () -> assertThat(pending).singleElement().satisfies(entry -> {
                            assertThat(entry.productId()).isEqualTo(productId);
                            assertThat(entry.document().getStockQuantity()).isZero();
                        }),
                        () -> assertThat(outcomes)
                                .as("orders=%s, stock=%s, pending products=%s", orderIds, stock, pending.size())
                                .containsExactly("order " + orderIds.getFirst(), "order " + orderIds.getFirst())
                );
            } finally {
                outbox.complete(pending);
            }
        }
    }

    private String createOrderOutcome(CreateOrderRequest request) {
        try {
            return "order " + orders.create(request).id();
        } catch (BusinessException failure) {
            return failure.errorCode().name();
        }
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

    @Test
    void stockFailureAllowsTheCallingTransactionToContinue() {
        long productId = 1013L;
        insertProductWithTenUnits(productId);
        var request = new CreateOrderRequest(1L, productId, 11, "outer-transaction");

        transaction.executeWithoutResult(status -> {
            assertThatThrownBy(() -> orders.create(request)).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
            jdbc.update("UPDATE products SET stock_quantity = 20 WHERE id = ?", productId);
        });

        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(20);
    }

    @Test
    @Sql(statements = "INSERT INTO products (id, name, price_amount, stock_quantity) VALUES (1012, 'Pool probe', 10, 10)")
    void measuresPoolAcquisitionsForNewOrderAndReplay(@Autowired MeterRegistry metrics) {
        var request = new CreateOrderRequest(1L, 1012L, 1, "pool-probe");
        var acquisitions = metrics.get("hikaricp.connections.acquire").tag("pool", "order-stock-outbox").timer();
        long beforeCreate = acquisitions.count();
        var created = orders.create(request);
        long afterCreate = acquisitions.count();
        var replayed = orders.create(request);
        long afterReplay = acquisitions.count();

        long newOrderCount = afterCreate - beforeCreate;
        long replayCount = afterReplay - afterCreate;
        outbox.complete(outbox.claim(10));
        System.out.printf("Order connection acquisitions: new=%d, replay=%d%n", newOrderCount, replayCount);
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(newOrderCount).isOne();
        assertThat(replayCount).isOne();
    }
}
