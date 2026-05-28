package com.baedal.support.dto;

import java.time.Instant;

/**
 * 표준 에러 응답 포맷.
 * 모든 4xx/5xx 응답을 일관된 구조로 반환하여 클라이언트가 동일한 파싱·핸들링 가능.
 */
public record ErrorResponse(
        String code,
        String message,
        String path,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, path, Instant.now());
    }
}
