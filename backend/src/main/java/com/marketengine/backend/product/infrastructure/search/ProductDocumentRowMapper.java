package com.marketengine.backend.product.infrastructure.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

final class ProductDocumentRowMapper {

    private ProductDocumentRowMapper() {}

    static ProductDocument map(ResultSet row, int rowNumber) throws SQLException {
        return new ProductDocument(
                row.getLong("id"),
                row.getString("name"),
                row.getBigDecimal("price_amount"),
                row.getInt("stock_quantity") > 0 && "ACTIVE".equals(row.getString("status")),
                row.getString("category"),
                row.getString("brand"),
                row.getString("color"),
                row.getString("gender"),
                row.getInt("popularity_score"),
                row.getObject("created_at", OffsetDateTime.class)
        );
    }
}
