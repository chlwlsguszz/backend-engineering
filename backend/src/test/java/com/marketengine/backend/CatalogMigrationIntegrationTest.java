package com.marketengine.backend;

import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CatalogMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private FluentConfiguration migrations(String schema) {
        return migrations(schema, 13);
    }

    private FluentConfiguration migrations(String schema, int productCount) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).locations("classpath:db/migration")
                .placeholders(Map.of("catalogProductCount", Integer.toString(productCount),
                        "catalogAsOf", "2026-11-01T00:00:00Z", "catalogSeed", "42"));
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void existingSeededCatalogUpgradesWithoutChangingItsRowsOrQueuedWork() {
        migrations("upgrade").target("10").load().migrate();
        var jdbc = jdbc();
        jdbc.update("insert into upgrade.product_search_outbox (product_id) values (1)");
        var before = jdbc.queryForList("select id, stock_quantity from upgrade.products order by id");
        var orders = jdbc.queryForList("select id, quantity, status from upgrade.orders order by id");
        var flyway = migrations("upgrade", 1000).load();
        flyway.migrate();
        flyway.validate();
        assertThat(before).hasSize(13);
        assertThat(jdbc.queryForList("select id, stock_quantity from upgrade.products order by id")).isEqualTo(before);
        assertThat(jdbc.queryForList("select id, quantity, status from upgrade.orders order by id")).isEqualTo(orders);
        assertThat(jdbc.queryForObject("select count(*) from upgrade.product_search_outbox where enqueued_at is not null", Long.class)).isOne();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void originalV8CatalogIsNotReplacedWhenTheDefaultSeedSizeChanges() {
        migrations("legacy").target("8").load().migrate();
        var jdbc = jdbc();
        var before = jdbc.queryForList("select id, stock_quantity from legacy.products order by id");
        var flyway = migrations("legacy", 1000).load();
        flyway.migrate();
        flyway.validate();
        assertThat(before).hasSize(13);
        assertThat(jdbc.queryForList("select id, stock_quantity from legacy.products order by id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from legacy.orders where requested_quantity <> quantity", Integer.class)).isZero();
    }

    @Test
    void emptyProfileBuildsTheSchemaWithoutBenchmarkData() {
        var flyway = migrations("empty_db").locations("classpath:db/migration", "classpath:db/empty").load();
        flyway.migrate();
        flyway.validate();
        assertThat(jdbc().queryForObject("select count(*) from empty_db.products", Long.class)).isZero();
        assertThat(jdbc().queryForObject("select count(*) from empty_db.catalog_seed_run", Long.class)).isZero();
        assertThat(jdbc().queryForObject("select count(*) from empty_db.orders", Long.class)).isZero();
    }
}
