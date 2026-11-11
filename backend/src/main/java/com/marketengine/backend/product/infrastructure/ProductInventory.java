package com.marketengine.backend.product.infrastructure;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ProductInventory {
    private final JdbcTemplate jdbc;

    public record Reservation(BigDecimal unitPrice, boolean availabilityChanged) {}

    public Reservation reserve(long productId, int units) {
        if (units < 1) {
            throw new IllegalArgumentException("Reservation must be positive");
        }
        List<Reservation> reservations = jdbc.query("""
                UPDATE products
                SET stock_quantity = stock_quantity - ?,
                    status = CASE WHEN stock_quantity = ? THEN 'OUT_OF_STOCK' ELSE 'ACTIVE' END,
                    version = version + 1, updated_at = greatest(clock_timestamp(), updated_at)
                WHERE id = ? AND status = 'ACTIVE' AND stock_quantity >= ?
                RETURNING price_amount, stock_quantity = 0 AS availability_changed
                """, (row, index) -> new Reservation(row.getBigDecimal("price_amount"),
                        row.getBoolean("availability_changed")), units, units, productId, units);
        if (!reservations.isEmpty()) {
            return reservations.getFirst();
        }
        List<String> statuses = jdbc.queryForList("SELECT status FROM products WHERE id = ?", String.class, productId);
        if (statuses.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found");
        }
        if (!List.of("ACTIVE", "OUT_OF_STOCK").contains(statuses.getFirst())) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOR_SALE);
        }
        throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock");
    }

    public boolean release(long productId, int units) {
        if (units < 1) {
            throw new IllegalArgumentException("Release must be positive");
        }
        List<Boolean> availabilityChanges = jdbc.queryForList("""
                UPDATE products
                SET stock_quantity = stock_quantity + ?,
                    status = CASE WHEN status = 'OUT_OF_STOCK' THEN 'ACTIVE' ELSE status END,
                    version = version + 1, updated_at = greatest(clock_timestamp(), updated_at)
                WHERE id = ? AND stock_quantity <= ?
                RETURNING status = 'ACTIVE' AND stock_quantity = ?
                """, Boolean.class, units, productId, Integer.MAX_VALUE - units, units);
        if (availabilityChanges.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Unable to restore inventory");
        }
        return availabilityChanges.getFirst();
    }
}
