package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.api.ProductDtos.UpdateProductRequest;
import com.marketengine.backend.product.application.ProductService;
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
    @Autowired private ProductService products;
    @Autowired private OrderCreateExecutor orderWriter;

    @BeforeEach
    void seedBuyer() {
        jdbc.update("""
            INSERT INTO members (id, email, name) VALUES (1, 'stock-buyer@local.test', 'buyer')
            ON CONFLICT (id) DO NOTHING
            """);
    }

    @Test
    void quantityChangesPreserveUnitPriceAndOriginalReplayAfterCancel() {
        long product = 1101L;
        insertProductWithTenUnits(product);
        var request = new CreateOrderRequest(1L, product, 3, "lifecycle");
        var initial = orders.create(request);
        checkAndCompleteOutbox(product, 7);
        jdbc.update("UPDATE products SET price_amount = 25 WHERE id = ?", product);
        var increased = orders.update(initial.id(), new UpdateOrderRequest(OrderStatus.CONFIRMED, 5));
        assertThat(increased.totalAmount()).isEqualByComparingTo("50");
        checkAndCompleteOutbox(product, 5);
        assertThat(orders.create(request).quantity()).isEqualTo(5);
        assertThat(orders.update(initial.id(), new UpdateOrderRequest(OrderStatus.CONFIRMED, 5)).quantity()).isEqualTo(5);
        assertThat(outbox.claim(10)).isEmpty();
        assertThatThrownBy(() -> orders.update(initial.id(), new UpdateOrderRequest(OrderStatus.CONFIRMED, 11)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        assertThat(orders.get(initial.id()).quantity()).isEqualTo(5);
        assertThat(outbox.claim(10)).isEmpty();
        orders.update(initial.id(), new UpdateOrderRequest(OrderStatus.CONFIRMED, 2));
        checkAndCompleteOutbox(product, 8);
        orders.delete(initial.id());
        checkAndCompleteOutbox(product, 10);
        orders.delete(initial.id());
        assertThat(orders.create(request).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outbox.claim(10)).isEmpty();
        assertThatThrownBy(() -> orders.update(initial.id(), new UpdateOrderRequest(OrderStatus.CREATED, 2)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void twoCancellationsRacingWithAnEditRestoreStockExactlyOnce() throws Exception {
        long product = 1102L;
        insertProductWithTenUnits(product);
        long order = orders.create(new CreateOrderRequest(1L, product, 3, "cancel-race")).id();
        checkAndCompleteOutbox(product, 7);
        try (var callers = Executors.newFixedThreadPool(3)) {
            var futures = transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", Long.class, order);
                var cancel1 = callers.submit(() -> orders.delete(order));
                var cancel2 = callers.submit(() -> orders.delete(order));
                var edit = callers.submit(() -> {
                    try {
                        orders.update(order, new UpdateOrderRequest(OrderStatus.CREATED, 5));
                    } catch (BusinessException ex) {
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    }
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                        SELECT count(DISTINCT pid) FROM pg_locks WHERE relation = 'orders'::regclass
                          AND mode = 'RowShareLock' AND granted AND cardinality(pg_blocking_pids(pid)) > 0
                        """, Integer.class) == 3);
                return List.of(cancel1, cancel2, edit);
            });
            for (var call : futures) call.get(10, TimeUnit.SECONDS);
        }
        assertThat(orders.get(order).status()).isEqualTo(OrderStatus.CANCELLED);
        checkAndCompleteOutbox(product, 10);
    }

    @Test
    void staleAdminWriteCannotOverwriteLastUnitAndCancellationPreservesDiscontinuation() {
        long product = 1103L;
        insertProductWithTenUnits(product);
        long version = products.get(product).version();
        var order = orders.create(new CreateOrderRequest(1L, product, 10, "admin-race"));
        checkAndCompleteOutbox(product, 0);
        var soldOut = products.get(product);
        assertThat(soldOut.status()).isEqualTo("OUT_OF_STOCK");
        var stale = new UpdateProductRequest("Replaced", BigDecimal.TEN, 10, "old stock", soldOut.category(),
                "ACME", "BLACK", "UNISEX", "ACTIVE", 0, version);
        assertThatThrownBy(() -> products.update(product, stale)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        var discontinue = new UpdateProductRequest("Replaced", BigDecimal.TEN, 0, "discontinued", soldOut.category(),
                "ACME", "BLACK", "UNISEX", "DISCONTINUED", 0, soldOut.version());
        products.update(product, discontinue);
        orders.delete(order.id());
        checkAndCompleteOutbox(product, 10);
        assertThat(products.get(product).status()).isEqualTo("DISCONTINUED");
        assertThatThrownBy(() -> orders.create(new CreateOrderRequest(1L, product, 1, "discontinued")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOR_SALE));
        assertThat(outbox.claim(10)).isEmpty();
    }

    @Test
    void orderCommittingWhileAdminFlushWaitsWinsTheVersionCheck() throws Exception {
        long product = 1104L;
        insertProductWithTenUnits(product);
        var previous = products.get(product);
        var oldView = new UpdateProductRequest("Changed name", BigDecimal.TEN, 10, "old inventory", previous.category(),
                "ACME", "BLACK", "UNISEX", "ACTIVE", 0, previous.version());
        try (var caller = Executors.newSingleThreadExecutor()) {
            var admin = transaction.execute(tx -> {
                // The real order writer joins this transaction and retains the inventory write lock.
                orderWriter.execute(new CreateOrderRequest(1L, product, 3, "admin-flush-race"));
                var pending = caller.submit(() -> products.update(product, oldView));
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                        SELECT count(DISTINCT pid) FROM pg_locks WHERE relation = 'products'::regclass
                          AND mode = 'RowExclusiveLock' AND granted AND cardinality(pg_blocking_pids(pid)) > 0
                        """, Integer.class) == 1);
                return pending;
            });
            assertThatThrownBy(() -> admin.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(OptimisticLockingFailureException.class);
        }
        assertThat(products.get(product).name()).isEqualTo("Test SKU");
        checkAndCompleteOutbox(product, 7);
    }

    private void checkAndCompleteOutbox(long productId, int units) {
        var entries = outbox.claim(10);
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.productId()).isEqualTo(productId);
            assertThat(entry.document().getStockQuantity()).isEqualTo(units);
        });
        outbox.complete(entries);
    }

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
                                WHERE relation = 'products'::regclass AND mode = 'RowExclusiveLock' AND granted
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
