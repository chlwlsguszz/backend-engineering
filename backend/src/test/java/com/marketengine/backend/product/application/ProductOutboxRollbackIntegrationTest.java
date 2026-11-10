package com.marketengine.backend.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import com.marketengine.backend.product.domain.ProductRepository;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

@SpringBootTest(properties = {
        "marketengine.elasticsearch.enabled=false",
        "spring.data.elasticsearch.repositories.enabled=false"
})
class ProductOutboxRollbackIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @MockitoBean
    private ProductSearchOutbox productSearchOutbox;

    @Test
    @Sql(statements = """
            INSERT INTO products (id, name, price_amount, stock_quantity, description, category,
                                  brand, color, gender, status, popularity_score, created_at, updated_at)
            VALUES (41001, 'rollback test', 10, 4, 'test', 'TOP', 'ACME', 'BLACK', 'UNISEX',
                    'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
            """)
    @Sql(statements = "DELETE FROM products WHERE id = 41001", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void delete_rollsBackFlushedProductDeletionWhenEnqueueFails() {
        long productId = 41001L;
        doThrow(new DataAccessResourceFailureException("outbox write failed"))
                .when(productSearchOutbox).enqueue(productId);

        assertThatThrownBy(() -> productService.delete(productId))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(productRepository.existsById(productId)).isTrue();
    }
}
