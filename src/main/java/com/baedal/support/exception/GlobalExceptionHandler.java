package com.baedal.support.exception;

import com.baedal.support.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 모든 컨트롤러 공통 예외 핸들러.
 * 표준 ErrorResponse 포맷으로 일관된 4xx/5xx 응답 보장.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * @Valid 검증 실패 — @NotBlank 등 위반. 400 응답.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining(", "));
        log.warn("[validation] {}", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ErrorResponse.of("VALIDATION_ERROR", fieldErrors, request.getRequestURI())
        );
    }

    /**
     * LLM 호출 실패 — 일시적 (네트워크·timeout 등). 503 응답.
     */
    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<ErrorResponse> handleTransientAi(
            TransientAiException ex,
            HttpServletRequest request
    ) {
        log.error("[ai-transient] {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ErrorResponse.of(
                        "LLM_UPSTREAM_UNAVAILABLE",
                        "LLM 호출에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
                        request.getRequestURI()
                )
        );
    }

    /**
     * LLM 호출 실패 — 영구적 (모델 미존재·인증 오류 등). 502 응답.
     */
    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<ErrorResponse> handleNonTransientAi(
            NonTransientAiException ex,
            HttpServletRequest request
    ) {
        log.error("[ai-non-transient] {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ErrorResponse.of(
                        "LLM_UPSTREAM_ERROR",
                        "LLM 처리 중 오류가 발생했습니다. 관리자에게 문의해주세요.",
                        request.getRequestURI()
                )
        );
    }

    /**
     * Fallback — 예상 못한 예외. 내부 정보 노출하지 않고 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("[unexpected] {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ErrorResponse.of(
                        "INTERNAL_ERROR",
                        "처리 중 오류가 발생했습니다.",
                        request.getRequestURI()
                )
        );
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
