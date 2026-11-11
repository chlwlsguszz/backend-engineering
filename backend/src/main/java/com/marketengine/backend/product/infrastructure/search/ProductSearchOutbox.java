package com.marketengine.backend.product.infrastructure.search;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductSearchOutbox {

    // On conflict the DEFAULT allocates a fresh revision after locking the existing row.
    private static final String ENQUEUE = """
            INSERT INTO product_search_outbox (product_id) VALUES (?)
            ON CONFLICT (product_id) DO UPDATE
            SET revision = DEFAULT,
                available_at = least(product_search_outbox.available_at, statement_timestamp()),
                retry_count = DEFAULT, lease_token = NULL
            """;

    private static final String CLAIM = """
            WITH candidates AS (
                SELECT product_id
                FROM product_search_outbox
                WHERE available_at <= statement_timestamp()
                ORDER BY available_at, product_id
                LIMIT ? FOR UPDATE SKIP LOCKED
            )
            UPDATE product_search_outbox o
            SET lease_token = ?, available_at = statement_timestamp() + interval '60 seconds'
            FROM candidates c
            WHERE o.product_id = c.product_id
            RETURNING o.product_id
            """;

    private static final String LOAD_CLAIMED = """
            SELECT c.product_id, c.revision, c.lease_token, p.id, p.name, p.price_amount,
                   p.stock_quantity, p.status, p.category, p.brand, p.color, p.gender, p.popularity_score, p.created_at
            FROM product_search_outbox c LEFT JOIN products p ON p.id = c.product_id
            WHERE c.product_id = ANY (?) AND c.lease_token = ?
            """;

    private static final String COMPLETE = """
            DELETE FROM product_search_outbox
            WHERE product_id = ? AND revision = ? AND lease_token = ?
            """;

    private static final String RETRY = """
            UPDATE product_search_outbox
            SET lease_token = NULL, retry_count = retry_count + 1,
                available_at = statement_timestamp() + interval '1 second' * least(60, 1 << least(retry_count, 6))
            WHERE product_id = ? AND revision = ? AND lease_token = ?
            """;

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long productId) {
        jdbc.update(ENQUEUE, productId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Entry> claim(int limit) {
        UUID leaseToken = UUID.randomUUID();
        List<Long> ids = jdbc.queryForList(CLAIM, Long.class, limit, leaseToken);
        if (ids.isEmpty()) {
            return List.of();
        }
        // A fresh snapshot after acquiring the row locks sees the commit that produced each claimed revision.
        return jdbc.query(LOAD_CLAIMED, (row, rowNumber) -> new Entry(
                        row.getLong("product_id"),
                        row.getLong("revision"),
                        row.getObject("lease_token", UUID.class),
                        row.getObject("id") == null ? null : ProductDocumentRowMapper.map(row, rowNumber)),
                new SqlArrayValue("bigint", ids.toArray()), leaseToken);
    }

    @Transactional
    public void complete(List<Entry> entries) {
        updateLeased(COMPLETE, entries);
    }

    @Transactional
    public void retry(List<Entry> entries) {
        updateLeased(RETRY, entries);
    }

    private void updateLeased(String sql, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(sql, entries, entries.size(), (statement, entry) -> {
            statement.setLong(1, entry.productId());
            statement.setLong(2, entry.revision());
            statement.setObject(3, entry.leaseToken());
        });
    }

    /** A null document means the product was deleted in the committed catalog. */
    public record Entry(long productId, long revision, UUID leaseToken, ProductDocument document) {}
}
