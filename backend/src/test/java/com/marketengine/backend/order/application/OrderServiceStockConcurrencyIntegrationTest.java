package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.product.domain.ProductRepository;

@SpringBootTest
@TestPropertySource(properties = {
        "marketengine.elasticsearch.enabled=false",
        "spring.data.elasticsearch.repositories.enabled=false",
        "marketengine.elasticsearch.verify-on-startup=false",
        "marketengine.elasticsearch.index.ensure-on-startup=false",
        "marketengine.cache.redis.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.cache.type=simple"
})
class OrderServiceStockConcurrencyIntegrationTest {

    private static final int INITIAL_STOCK = 5;
    private static final int CONCURRENT_REQUESTS = 20;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM \"orders\"");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM members");
    }

    @Test
    void create_doesNotOversellUnderConcurrentRequests() throws Exception {
        long memberId = insertMember("order-it-concurrent@test.com");
        long productId = insertProduct(INITIAL_STOCK);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                tasks.add(() -> {
                    try {
                        orderService.create(new CreateOrderRequest(memberId, productId, 1));
                        return true;
                    } catch (RuntimeException ex) {
                        return false;
                    }
                });
            }

            List<Future<Boolean>> results = executor.invokeAll(tasks);
            long successCount = results.stream()
                    .mapToLong(future -> {
                        try {
                            return future.get() ? 1L : 0L;
                        } catch (Exception ex) {
                            return 0L;
                        }
                    })
                    .sum();

            assertThat(successCount).isEqualTo(INITIAL_STOCK);
            assertThat(orderRepository.count()).isEqualTo(INITIAL_STOCK);
            assertThat(loadStock(productId)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private long insertMember(String email) {
        jdbcTemplate.update(
                """
                INSERT INTO members (email, password_hash, name, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                email,
                "pw",
                "order-it"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    private long insertProduct(int stockQuantity) {
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    name, price_amount, stock_quantity, description,
                    category, brand, color, gender, status, popularity_score,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                "order-it-concurrent-product",
                100.00,
                stockQuantity,
                "integration test",
                "TOP",
                "BRAND",
                "BLACK",
                "UNISEX",
                "ACTIVE",
                0
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE name = ?",
                Long.class,
                "order-it-concurrent-product"
        );
    }

    private int loadStock(long productId) {
        return productRepository.findById(productId).orElseThrow().getStockQuantity();
    }
}
