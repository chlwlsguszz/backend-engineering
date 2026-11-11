package com.marketengine.backend.order.application;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@MockitoBean(types = ProductSearchOutbox.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "marketengine.elasticsearch.enabled=false", "spring.data.elasticsearch.repositories.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "marketengine.order.lock-timeout=150ms", "marketengine.order.statement-timeout=1s"
})
class OrderPerformanceIntegrationTest extends OrderPostgresTest {
    @Autowired private OrderService orders;
    @Autowired private OrderTransactionLimits limits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManagerFactory entityManagers;
    @Autowired private MockMvc api;
    @Autowired private ObjectMapper json;
    private long member;
    private long product;

    @BeforeEach
    void seed() {
        jdbc.update("delete from orders");
        jdbc.update("delete from product_search_outbox");
        jdbc.update("delete from products");
        jdbc.update("delete from members");
        member = jdbc.queryForObject("""
                insert into members(email,name) values ('order-performance@test.local','buyer') returning id
                """, Long.class);
        product = insertProduct("Order test");
    }

    @Test
    void replayReadsOneProjectionAndNoMemberOrProductEntities() {
        var request = new CreateOrderRequest(member, product, 1, "projection");
        var created = orders.create(request);
        orders.update(created.id(), new UpdateOrderRequest(OrderStatus.CONFIRMED, 2));
        var statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        long entityLoads = statistics.getEntityLoadCount();
        long statements = statistics.getPrepareStatementCount();

        var replay = orders.create(request);

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.quantity()).isEqualTo(2);
        assertThat(replay.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(statistics.getEntityLoadCount() - entityLoads).isZero();
        assertThat(statistics.getPrepareStatementCount() - statements).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "cancel"})
    void lockedProductTimesOutAndIndependentOrdersCanStillCommit(String operation) throws Exception {
        var existing = orders.create(new CreateOrderRequest(member, product, 1, "existing"));
        long independent = insertProduct("Independent test");
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var held = caller.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from products where id = ? for update", Long.class, product);
                acquired.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the held lock");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                assertThat(acquired.await(3, TimeUnit.SECONDS)).isTrue();
                var request = switch (operation) {
                    case "create" -> post("/api/orders").content(json.writeValueAsString(
                            new CreateOrderRequest(member, product, 1, "blocked")));
                    case "update" -> put("/api/orders/{id}", existing.id()).content(json.writeValueAsString(
                            new UpdateOrderRequest(OrderStatus.CONFIRMED, 2)));
                    case "cancel" -> delete("/api/orders/{id}", existing.id());
                    default -> throw new AssertionError(operation);
                };
                api.perform(request.contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "1"))
                        .andExpect(jsonPath("$.error.code").value("ORDER_BUSY"))
                        .andExpect(jsonPath("$.error.details.retryable").value(true));
                assertThat(orders.create(new CreateOrderRequest(member, independent, 1, "independent")).id())
                        .isNotNull();
                assertThat(orders.get(existing.id()).quantity()).isEqualTo(1);
                assertThat(orders.get(existing.id()).status()).isEqualTo(OrderStatus.CREATED);
                assertThat(jdbc.queryForObject("select stock_quantity from products where id = ?", Integer.class, product))
                        .isEqualTo(9);
            } finally {
                release.countDown();
            }
            held.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void statementDeadlineCancelsWorkAndLocalSettingsDoNotLeakToThePool() {
        String previousLockTimeout = jdbc.queryForObject("show lock_timeout", String.class);
        String previousStatementTimeout = jdbc.queryForObject("show statement_timeout", String.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            limits.apply();
            assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("150ms");
            assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("1s");
            jdbc.execute("select pg_sleep(2)");
        })).isInstanceOf(QueryTimeoutException.class);

        assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo(previousLockTimeout);
        assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo(previousStatementTimeout);
    }

    private long insertProduct(String name) {
        return jdbc.queryForObject("""
                insert into products(name,price_amount,stock_quantity) values (?,10,10) returning id
                """, Long.class, name);
    }
}
