package com.baedal.support.guardrail;

/**
 * Guardrail이 입력을 차단하거나 치명적 문제를 탐지했을 때 던지는 예외.
 * <p>
 * 이 예외는 Advisor 체인 내부에서 바로 throw되지 않고,
 * InputGuardrailAdvisor가 체인을 우회(short-circuit)하고 정해진 안내 문구를 반환하는
 * 데에 쓰인다. Guardrail 차단 사유는 로그와 reason 필드로 남긴다.
 */
public class GuardrailException extends RuntimeException {

    private final String reason;

    public GuardrailException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}