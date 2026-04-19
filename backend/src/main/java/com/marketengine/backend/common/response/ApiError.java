package com.marketengine.backend.common.response;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * API 오류 본문. 검증 실패 시 {@code details}에 필드별 메시지 등을 담습니다.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        String message,
        Map<String, Object> details
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Map.of());
    }

    public static ApiError of(String code, String message, Map<String, Object> details) {
        return new ApiError(code, message, details);
    }
}
