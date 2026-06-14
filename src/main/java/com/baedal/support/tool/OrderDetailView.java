package com.baedal.support.tool;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tool 응답 DTO — LLM이 직접 읽는 구조이므로 필드명은 "LLM이 이해할 수 있는 자연어 키"로 둔다.
 * 내부 도메인 모델({@code Order})을 그대로 노출하지 않는다:
 * (1) 취소 이력/라이더 좌표 등 민감 정보를 필터링하기 위해
 * (2) LLM 입력 토큰을 줄이기 위해
 * <p>
 * {@code error}가 true이면 조회 중 시스템 오류가 발생한 것이다(주문 "없음"은 null로 구분).
 */
public record OrderDetailView(
        boolean error,
        ErrorKind errorKind,
        String orderId,
        String storeName,
        List<Line> items,
        int totalAmount,
        String status,
        LocalDateTime orderedAt,
        LocalDateTime estimatedDeliveryAt
) {
    public record Line(String menuName, int quantity, int unitPrice) {}

    /**
     * 조회 중 시스템 오류가 발생했을 때.
     * {@code errorKind}로 재시도 가능 여부({@link ErrorKind#TRANSIENT})와 영구 결함({@link ErrorKind#PERMANENT})을 구분한다.
     */
    public static OrderDetailView error(String orderId, ErrorKind errorKind) {
        return new OrderDetailView(true, errorKind, orderId, null, List.of(), 0, null, null, null);
    }
}