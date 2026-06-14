package com.baedal.support.dto;

/**
 * 모든 텍스트 엔드포인트(/chat, /chat/stream, /support, /assistant) 공용 요청 DTO.
 * <p>
 * Round 5: message 의 빈/길이/Injection 검증은 Bean Validation(@NotBlank)이 아니라
 * {@code InputGuardrail} 이 단일 소유한다. @NotBlank 를 두면 빈 입력이 400(validation)으로
 * Guardrail 의 EMPTY_INPUT 보다 먼저 막혀, 차단 사유·Fallback·로그가 두 층으로 갈라진다.
 * 그래서 @NotBlank 를 제거(완화)해 빈 입력도 Guardrail 의 EMPTY_INPUT 로 일관되게 차단한다.
 */
public record ChatRequest(String message) {}
