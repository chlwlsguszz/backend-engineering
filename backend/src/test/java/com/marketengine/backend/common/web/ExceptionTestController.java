package com.marketengine.backend.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.PathVariable;
import java.sql.SQLException;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.common.web.support.ValidatedBody;

import jakarta.validation.Valid;

/**
 * {@link GlobalExceptionHandler} 동작 확인용 — 테스트 소스에만 둡니다.
 */
@RestController
@RequestMapping("/__exception-test")
class ExceptionTestController {

    @GetMapping("/business")
    void business() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "not found");
    }

    @GetMapping("/lock-timeout")
    void lockTimeout() {
        throw new CannotAcquireLockException("lock deadline exceeded");
    }

    @GetMapping("/statement-timeout")
    void statementTimeout() {
        throw new QueryTimeoutException("statement deadline exceeded");
    }

    @GetMapping("/uncategorized/{sqlState}")
    void uncategorized(@PathVariable String sqlState) {
        throw new UncategorizedSQLException("test", "test query", new SQLException("driver failure", sqlState));
    }

    @PostMapping("/validation")
    void validation(@Valid @RequestBody ValidatedBody body) {
        // never reached with invalid body
    }
}
