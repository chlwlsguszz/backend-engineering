package com.marketengine.backend.product.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(ProductSearchOutboxIntegrationTest.Config.class)
class ProductSearchOutboxIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(ProductSearchOutbox.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Autowired ProductSearchOutbox outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void createSchema(@Autowired DataSource dataSource) {
        new JdbcTemplate(dataSource).execute("""
                CREATE TABLE products (
                    id bigint PRIMARY KEY, name text NOT NULL, price_amount numeric NOT NULL DEFAULT 12,
                    stock_quantity int NOT NULL DEFAULT 3, status text NOT NULL DEFAULT 'ACTIVE',
                    category text DEFAULT 'TOP', brand text DEFAULT 'Acme',
                    color text DEFAULT 'BLACK', gender text DEFAULT 'UNISEX', popularity_score int DEFAULT 0,
                    created_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V10__product_search_outbox.sql"))
                .execute(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE products, product_search_outbox");
    }

    @Test
    void enqueueSharesTheProductTransactionAndRequiresOne() {
        assertThatThrownBy(() -> outbox.enqueue(1)).isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO products (id, name) VALUES (1, 'Rolled back')");
            outbox.enqueue(1);
            tx.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isZero();
        assertThat(outbox.claim(10)).isEmpty();
    }

    @Test
    void coalescesChangesAndKeepsADeletionDespiteLateAcknowledgement() {
        insertProduct(1);
        var original = outbox.claim(10).getFirst();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("UPDATE products SET name = 'New name' WHERE id = 1");
            outbox.enqueue(1);
        });
        var changed = outbox.claim(10).getFirst();
        assertThat(changed.revision()).isGreaterThan(original.revision());
        assertThat(changed.document().getName()).isEqualTo("New name");
        assertThat(changed.document().getCreatedAt()).isNotNull();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM products WHERE id = 1");
            outbox.enqueue(1);
        });

        outbox.complete(List.of(original));
        outbox.retry(List.of(changed));
        var deleted = outbox.claim(10).getFirst();
        assertThat(deleted.revision()).isGreaterThan(changed.revision());
        assertThat(deleted.document()).isNull();
        outbox.complete(List.of(deleted));
        assertThat(outbox.claim(10)).isEmpty();
    }

    @Test
    void workerCrashCanBeRetriedAndLateOwnerCannotAcknowledge() {
        insertProduct(1);
        var firstClaim = outbox.claim(10);
        assertThat(outbox.claim(10)).isEmpty();
        jdbc.update("UPDATE product_search_outbox SET available_at = now() WHERE product_id = 1");
        var reclaimed = outbox.claim(10);
        assertThat(reclaimed.getFirst().revision()).isEqualTo(firstClaim.getFirst().revision());
        assertThat(reclaimed.getFirst().leaseToken()).isNotEqualTo(firstClaim.getFirst().leaseToken());

        outbox.complete(firstClaim);
        outbox.retry(reclaimed);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM product_search_outbox", Integer.class)).isOne();
        jdbc.update("UPDATE product_search_outbox SET available_at = now() WHERE product_id = 1");
        var retried = outbox.claim(10);
        assertThat(retried.getFirst().document().getName()).isEqualTo("Product");
        outbox.complete(retried);
        insertProduct(2);
        assertThat(outbox.claim(10).getFirst().revision()).isGreaterThan(retried.getFirst().revision());
    }

    @Test
    void skipsAnOutboxRowLockedByAnotherTransaction(@Autowired DataSource dataSource) throws Exception {
        insertProduct(1);
        insertProduct(2);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT product_id FROM product_search_outbox WHERE product_id = 1 FOR UPDATE");
                 var locked = statement.executeQuery()) {
                assertThat(locked.next()).isTrue();
                assertThat(outbox.claim(1)).extracting(ProductSearchOutbox.Entry::productId).containsExactly(2L);
            } finally {
                connection.rollback();
            }
        }
        assertThat(outbox.claim(1)).extracting(ProductSearchOutbox.Entry::productId).containsExactly(1L);
    }

    private void insertProduct(long productId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO products (id, name) VALUES (?, 'Product')", productId);
            outbox.enqueue(productId);
        });
    }
}
