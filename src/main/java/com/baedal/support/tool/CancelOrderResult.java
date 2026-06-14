package com.baedal.support.tool;

/**
 * 주문 취소 Tool의 결과.
 * <p>
 * 성공/실패를 boolean으로만 돌리면 LLM이 상황을 설명하지 못한다.
 * {@code outcome}을 문자열 enum 값으로 두어 LLM이 그대로 고객에게 설명할 수 있도록 한다.
 */
public record CancelOrderResult(
        String orderId,
        Outcome outcome,
        String message,
        ErrorKind errorKind
) {
    /**
     * {@code errorKind}가 없는 경우(성공·업무 실패 등 outcome이 ERROR가 아닐 때) 생성한다.
     * outcome=ERROR일 때만 {@link ErrorKind}로 재시도 가능 여부를 구분한다.
     */
    public CancelOrderResult(String orderId, Outcome outcome, String message) {
        this(orderId, outcome, message, null);
    }

    public enum Outcome {
        CANCELED,            // 이번 호출에서 취소됨
        ALREADY_CANCELED,    // 이미 취소되어 있었음 (멱등 — 에러 아님)
        NOT_CANCELABLE,      // 조리 시작 이후 등 취소 불가
        NOT_FOUND,           // 주문번호 없음
        ERROR                // 서비스 내부 오류 — 상담사 연결 권장
    }
}
