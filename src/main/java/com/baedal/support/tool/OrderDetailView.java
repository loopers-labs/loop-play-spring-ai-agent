package com.baedal.support.tool;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tool 응답 DTO — LLM이 직접 읽는 구조이므로 필드명은 "LLM이 이해할 수 있는 자연어 키"로 둔다.
 * 내부 도메인 모델({@code Order})을 그대로 노출하지 않는다:
 * (1) 취소 이력/라이더 좌표 등 민감 정보를 필터링하기 위해
 * (2) LLM 입력 토큰을 줄이기 위해
 */
public record OrderDetailView(
        String orderId,
        String storeName,
        List<Line> items,
        int totalAmount,
        String status,
        LocalDateTime orderedAt,
        LocalDateTime estimatedDeliveryAt
) {
    public record Line(String menuName, int quantity, int unitPrice) {}
}
