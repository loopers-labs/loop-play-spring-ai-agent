package com.baedal.support.domain;

/**
 * 주문의 생명 주기 상태.
 * <p>
 * CREATED → ACCEPTED → COOKING → DELIVERING → DELIVERED
 * 중간에 CANCELED가 발생할 수 있으며, 일단 CANCELED가 되면 다른 상태로 전이되지 않는다.
 */
public enum OrderStatus {
    CREATED,     // 주문 생성, 사장님 수락 전
    ACCEPTED,    // 사장님 수락
    COOKING,     // 조리 중
    DELIVERING,  // 배달 중
    DELIVERED,   // 배달 완료
    CANCELED     // 취소됨
}