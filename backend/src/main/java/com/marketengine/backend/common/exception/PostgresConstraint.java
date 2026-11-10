package com.marketengine.backend.common.exception;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.DataIntegrityViolationException;

public final class PostgresConstraint {
    private PostgresConstraint() {}

    public static boolean matches(DataIntegrityViolationException failure, PSQLState state,
                                  String table, String constraint) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException pg) {
                var error = pg.getServerErrorMessage();
                return state.getState().equals(pg.getSQLState()) && error != null
                        && table.equals(error.getTable()) && constraint.equals(error.getConstraint());
            }
        }
        return false;
    }
}
