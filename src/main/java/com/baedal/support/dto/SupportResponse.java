package com.baedal.support.dto;

import java.util.List;

public record SupportResponse(
        String summary,
        String customerMessage,
        Category category,
        Intent intent,
        List<Category> relatedCategories,
        Urgency urgency,
        String nextAction,
        List<String> neededInfo,
        List<String> missingInfo,
        Integer estimatedResolutionMinutes,
        ConfidenceLevel confidenceLevel,
        RecommendedRouting recommendedRouting
) {
    /**
     * record 정규화 — LLM 응답의 null/가변 컬렉션/음수 값을 안전하게 처리.
     */
    public SupportResponse {
        relatedCategories = relatedCategories == null ? List.of() : List.copyOf(relatedCategories);
        neededInfo = neededInfo == null ? List.of() : List.copyOf(neededInfo);
        missingInfo = missingInfo == null ? List.of() : List.copyOf(missingInfo);
        if (estimatedResolutionMinutes != null && estimatedResolutionMinutes < 0) {
            estimatedResolutionMinutes = 0;
        }
    }

    /**
     * 문의의 1차 업무 도메인.
     * 운영·후속 처리 흐름이 달라지는 영역을 별도로 분리한다.
     */
    public enum Category {
        ORDER, DELIVERY, PAYMENT, REFUND, CLAIM,
        MENU, STORE, COUPON, ACCOUNT, SYSTEM, ETC
    }

    public enum Urgency { LOW, NORMAL, HIGH, CRITICAL }

    /**
     * 카테고리 안의 구체적 고객 의도.
     * Tool Calling 단계에서 Intent별로 호출할 외부 API/도구를 매핑한다.
     */
    public enum Intent {
        // ORDER
        ORDER_INQUIRY, ORDER_CHANGE, ORDER_CANCEL,
        // DELIVERY
        DELIVERY_LOCATION, DELIVERY_DELAY, DELIVERY_ADDRESS,
        // PAYMENT
        PAYMENT_FAILURE, PAYMENT_DUPLICATE, PAYMENT_METHOD,
        // REFUND
        REFUND_STATUS, REFUND_REQUEST, REFUND_DURATION,
        // CLAIM
        CLAIM_MISSING_ITEM, CLAIM_WRONG_DELIVERY, CLAIM_DAMAGED_FOOD,
        CLAIM_QUALITY, CLAIM_COMPENSATION,
        // MENU
        MENU_INFO, MENU_SOLD_OUT, MENU_ALLERGY,
        // STORE
        STORE_HOURS, STORE_CLOSED, STORE_CONTACT,
        // COUPON
        COUPON_APPLY, COUPON_BALANCE,
        // ACCOUNT
        ACCOUNT_LOGIN, ACCOUNT_INFO, ACCOUNT_ADDRESS,
        // SYSTEM
        SYSTEM_APP_ERROR, SYSTEM_NOTIFICATION,
        // ETC
        ETC_OTHER
    }

    /**
     * 응답에 대한 LLM의 자기 확신 수준.
     * LOW이면 자동 처리보다는 상담사 검토를 권장.
     * 주의: LLM의 자기평가는 과신 편향이 있어 단독 신호로는 약함.
     */
    public enum ConfidenceLevel { LOW, MEDIUM, HIGH }

    /**
     * 상담 처리 라우팅 — 누가/어디서 후속 처리를 해야 하는가.
     * urgency(긴급도)와 별개의 축으로, 책임 주체를 명시한다.
     */
    public enum RecommendedRouting {
        AUTO,                  // 에이전트 답변만으로 종결 가능 (또는 Tool Calling으로 자동 조회)
        AGENT_REVIEW,          // 상담원 검토 필요
        MANAGER_REVIEW,        // 관리자(매니저) 확인 필요 — 보상·정책 결정
        DELIVERY_PARTNER,      // 배달 대행사 확인 필요 — 라이더·배송 관련
        STORE_CONFIRMATION     // 매장 확인 필요 — 조리·메뉴·재고 관련
    }
}
