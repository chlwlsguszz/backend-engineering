package com.marketengine.backend.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.product.domain.ProductRepository;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

@SpringBootTest
@MockitoBean(types = ProductSearchOutbox.class)
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
class OrderServiceIdempotencyIntegrationTest {

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
    void create_returnsSameOrderWhenIdempotencyKeyIsReused() {
        long memberId = insertMember("order-it-idempotent@test.com");
        long productId = insertProduct(10);
        CreateOrderRequest request = new CreateOrderRequest(memberId, productId, 1, "dup-key-1");

        OrderResponse first = orderService.create(request);
        OrderResponse second = orderService.create(request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(orderRepository.count()).isOne();
        assertThat(loadStock(productId)).isEqualTo(9);
    }

    @Test
    void create_rejectsIdempotencyKeyReuseWithDifferentPayload() {
        long memberId = insertMember("order-it-idempotent-mismatch@test.com");
        long productId = insertProduct(10);
        orderService.create(new CreateOrderRequest(memberId, productId, 1, "dup-key-2"));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(memberId, productId, 2, "dup-key-2")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));

        assertThat(orderRepository.count()).isOne();
        assertThat(loadStock(productId)).isEqualTo(9);
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
                "order-it-idempotent-product",
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
                "order-it-idempotent-product"
        );
    }

    private int loadStock(long productId) {
        return productRepository.findById(productId).orElseThrow().getStockQuantity();
    }
}
