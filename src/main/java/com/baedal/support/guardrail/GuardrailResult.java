package com.baedal.support.guardrail;

/**
 * Guardrail 검사 결과.
 * <p>
 * - {@code allowed=true}: 통과 → 체인 진행
 * - {@code allowed=false}: 차단 → {@code fallbackMessage}로 즉시 응답
 *
 * @param allowed         통과 여부
 * @param reason          차단/통과 사유 (로그/감사용)
 * @param fallbackMessage 차단 시 고객에게 보낼 안내 문구 (통과 시 null)
 */
public record GuardrailResult(boolean allowed, String reason, String fallbackMessage) {

    public static GuardrailResult allow(String reason) {
        return new GuardrailResult(true, reason, null);
    }

    public static GuardrailResult block(String reason, String fallbackMessage) {
        return new GuardrailResult(false, reason, fallbackMessage);
    }
}
