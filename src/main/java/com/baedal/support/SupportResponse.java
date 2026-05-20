package com.baedal.support;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// TODO [1단계]: 필드 1개 이상을 의미 있게 추가하라.
//
// 예시:
// - estimatedResolutionMinutes (예상 해결 시간)
// - suggestedCompensationType (보상 유형 제안)
// - confidenceLevel (응답 확신도)
//
// 설계 결정 문서에 "왜 이 필드를 추가했는가?"를 기록하라.
public record SupportResponse(
        String summary,
        Category category,
        Urgency urgency,
        String nextAction,
        AffectedParty affectedParty,
        boolean requiresHumanVerification,
        List<String> neededInfo
) {
    // TODO [1단계]: Category enum 값이 배달 상담 도메인에 적절한지 검토하라.
    // 실제 배달 상담에서 빠진 카테고리는 없는가?
    // 설계 결정 문서에 "왜 이 5개인가?"를 기록하라.
    public enum Category { ORDER, DELIVERY, REFUND, PAYMENT, CANCEL, ETC }
    public enum Urgency  { LOW, NORMAL, HIGH, CRITICAL }
    public enum AffectedParty  { PLATFORM }
}
