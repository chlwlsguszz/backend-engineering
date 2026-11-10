package com.marketengine.backend.product.infrastructure.search;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!reindex")
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
class ProductOutboxMetrics {
    private final JdbcTemplate jdbc;
    private Snapshot snapshot;
    private long lastRead;

    ProductOutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("product.outbox.pending", this, m -> m.read().pending()).register(registry);
        Gauge.builder("product.outbox.oldest.age", this, m -> m.read().ageSeconds())
                .baseUnit("seconds").register(registry);
    }

    // Both gauges share one read per scrape interval, independent of the ES worker's progress.
    private synchronized Snapshot read() {
        long now = System.nanoTime();
        if (snapshot == null || now - lastRead >= TimeUnit.SECONDS.toNanos(2)) {
            snapshot = jdbc.queryForObject("""
                    SELECT count(*), coalesce(extract(epoch FROM statement_timestamp() - min(enqueued_at)), 0)
                    FROM product_search_outbox
                    """, (row, i) -> new Snapshot(row.getLong(1), row.getDouble(2)));
            lastRead = now;
        }
        return snapshot;
    }

    private record Snapshot(long pending, double ageSeconds) {}
}
