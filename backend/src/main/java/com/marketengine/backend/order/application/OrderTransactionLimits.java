package com.marketengine.backend.order.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/** PostgreSQL timeouts scoped to the current order transaction, using its existing connection. */
@Component
public class OrderTransactionLimits {
    private final JdbcTemplate jdbc;
    private final String lockTimeout;
    private final String statementTimeout;

    public OrderTransactionLimits(JdbcTemplate jdbc,
            @Value("${marketengine.order.lock-timeout:500ms}") Duration lockTimeout,
            @Value("${marketengine.order.statement-timeout:3s}") Duration statementTimeout) {
        Assert.isTrue(lockTimeout.toMillis() > 0, "Order lock timeout must be at least 1ms");
        Assert.isTrue(statementTimeout.toMillis() > lockTimeout.toMillis(),
                "Order statement timeout must exceed lock timeout");
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
        this.statementTimeout = statementTimeout.toMillis() + "ms";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply() {
        // The final true makes both settings LOCAL: commit/rollback restores the pooled connection.
        jdbc.queryForMap("""
                select set_config('lock_timeout', ?, true) as lock_timeout,
                       set_config('statement_timeout', ?, true) as statement_timeout
                """, lockTimeout, statementTimeout);
    }
}
