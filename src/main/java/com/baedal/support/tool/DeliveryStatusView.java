package com.baedal.support.tool;

import java.time.LocalDateTime;

/**
 * 배달 상태 조회 Tool 응답 DTO.
 * <p>
 * {@code riderLocation}은 배달 중(DELIVERING)일 때만 유효하며, 그 외 상태에서는 null이다.
 * {@code error}가 true이면 조회 중 시스템 오류가 발생한 것이다(주문 "없음"은 null로 구분).
 */
public record DeliveryStatusView(
        boolean error,
        ErrorKind errorKind,
        String orderId,
        String status,
        String riderLocation,
        LocalDateTime estimatedDeliveryAt,
        String message
) {

    /**
     * 조회 중 시스템 오류가 발생했을 때.
     * {@code errorKind}에 따라 재시도/상담사 연결 안내 문구를 달리한다.
     */
    public static DeliveryStatusView error(String orderId, ErrorKind errorKind) {
        String message = errorKind == ErrorKind.TRANSIENT
                ? "조회 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
                : "조회 중 오류가 발생했습니다. 상담사에게 연결해 드리겠습니다.";

        return new DeliveryStatusView(true, errorKind, orderId, null, null, null, message);
    }
}