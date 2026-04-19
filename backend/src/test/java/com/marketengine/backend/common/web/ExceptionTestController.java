package com.marketengine.backend.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/validation")
    void validation(@Valid @RequestBody ValidatedBody body) {
        // never reached with invalid body
    }
}
