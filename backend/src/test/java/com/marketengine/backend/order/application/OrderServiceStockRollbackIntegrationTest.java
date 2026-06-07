package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.domain.Order;
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
class OrderServiceStockRollbackIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @SpyBean
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM \"orders\"");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM members");
    }

    @AfterEach
    void resetSpies() {
        reset(orderRepository);
    }

    @Test
    void create_rollsBackStockWhenOrderPersistFails() {
        long memberId = insertMember("order-it-rollback@test.com");
        long productId = insertProduct(10);

        doThrow(new RuntimeException("simulate persist failure"))
                .when(orderRepository)
                .save(any(Order.class));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(memberId, productId, 1, "it-rollback")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulate persist failure");

        assertThat(loadStock(productId)).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
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
                "order-it-product",
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
                "order-it-product"
        );
    }

    private int loadStock(long productId) {
        return productRepository.findById(productId).orElseThrow().getStockQuantity();
    }
}
