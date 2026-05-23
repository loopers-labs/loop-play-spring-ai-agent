package com.baedal.support;

import java.util.List;

public record SupportResponse(
        String summary,
        Category category,
        Urgency urgency,
        String nextAction,
        List<String> neededInfo,
        CustomerSentiment customerSentiment, // 고객 감정 상태
        int estimatedResolutionMinutes, // 예상 소요시간
        boolean requiresHumanAgent // 사람 상담사 연결이 필요하면 true
) {

    public enum Category         { ORDER, DELIVERY, CANCELLATION, REFUND, PAYMENT, ETC }
    public enum Urgency          { LOW, NORMAL, HIGH, CRITICAL }
    public enum CustomerSentiment { POSITIVE, NEUTRAL, FRUSTRATED, ANGRY }
}
