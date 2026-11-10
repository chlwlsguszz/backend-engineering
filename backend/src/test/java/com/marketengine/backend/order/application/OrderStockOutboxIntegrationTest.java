package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
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
        "spring.datasource.hikari.maximum-pool-size=16",
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

    // With ample stock the losing requests reach the UNIQUE index on INSERT.
    // With the last unit the loser raises INSUFFICIENT_STOCK before the INSERT.
    @ParameterizedTest(name = "{2} concurrent duplicate orders with initial stock {1}")
    @CsvSource({"1003, 1, 2", "1014, 10, 10"})
    void concurrentDuplicateOrdersReturnTheSameOrder(long productId, int initialStock, int callerCount) throws Exception {
        jdbc.update("INSERT INTO products (id, name, price_amount, stock_quantity) VALUES (?, 'Concurrent SKU', 10, ?)",
                productId, initialStock);
        var request = new CreateOrderRequest(1L, productId, 1, "concurrent-duplicate-" + productId);

        try (var callers = Executors.newFixedThreadPool(callerCount)) {
            var replies = transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM products WHERE id = ? FOR UPDATE", Long.class, productId);
                var submitted = IntStream.range(0, callerCount)
                        .mapToObj(i -> callers.submit(() -> createOrderOutcome(request)))
                        .toList();
                // The gate holds all requests after the idempotency lookup and before the stock check.
                await().alias("all orders waiting for the product row").pollInterval(Duration.ofMillis(25))
                        .atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                                SELECT count(DISTINCT pid) FROM pg_locks
                                WHERE relation = 'products'::regclass AND mode = 'RowShareLock' AND granted
                                  AND cardinality(pg_blocking_pids(pid)) > 0
                                """, Integer.class) == callerCount);
                return submitted;
            });
            List<String> outcomes = new ArrayList<>(callerCount);
            for (var reply : replies) {
                outcomes.add(reply.get(10, TimeUnit.SECONDS));
            }
            var orderIds = jdbc.queryForList("SELECT id FROM orders WHERE product_id = ?", Long.class, productId);
            var stock = jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId);
            var pending = outbox.claim(10);
            try {
                assertAll(
                        () -> assertThat(orderIds).hasSize(1),
                        () -> assertThat(stock).isEqualTo(initialStock - request.quantity()),
                        () -> assertThat(pending).singleElement().satisfies(entry -> {
                            assertThat(entry.productId()).isEqualTo(productId);
                            assertThat(entry.document().getStockQuantity()).isEqualTo(initialStock - request.quantity());
                        }),
                        () -> assertThat(outcomes)
                                .as("orders=%s, stock=%s, pending products=%s", orderIds, stock, pending.size())
                                .hasSize(callerCount)
                                .containsOnly("order " + orderIds.getFirst())
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
    @Sql(statements = "CREATE UNIQUE INDEX uk_test_idempotency_product ON orders (product_id) WHERE product_id = 1015")
    @Sql(statements = "DROP INDEX uk_test_idempotency_product", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void propagatesUnrelatedUniqueViolationEvenWhenItsNameContainsIdempotency() {
        long productId = 1015L;
        insertProductWithTenUnits(productId);
        var first = orders.create(new CreateOrderRequest(1L, productId, 1, "unique-index-first"));
        outbox.complete(outbox.claim(10));

        var second = new CreateOrderRequest(1L, productId, 1, "unique-index-second");
        assertThatThrownBy(() -> orders.create(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOfSatisfying(PSQLException.class, cause -> {
                    assertThat(cause.getSQLState()).isEqualTo("23505");
                    assertThat(cause.getServerErrorMessage().getConstraint()).isEqualTo("uk_test_idempotency_product");
                });
        assertThat(jdbc.queryForList("SELECT id FROM orders WHERE product_id = ?", Long.class, productId))
                .containsExactly(first.id());
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(9);
        assertThat(outbox.claim(10)).isEmpty();
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
