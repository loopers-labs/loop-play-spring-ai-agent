package com.baedal.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SupportServiceException.class)
    public ResponseEntity<ErrorResponse> handleSupportServiceException(SupportServiceException e) {
        log.error("SupportServiceException", e);
        return ResponseEntity.status(503).body(new ErrorResponse("SERVICE_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("입력 검증 실패");
        return ResponseEntity.status(400).body(new ErrorResponse("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity.status(500).body(new ErrorResponse("INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다."));
    }
}
