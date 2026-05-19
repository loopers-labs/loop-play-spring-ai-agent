package com.baedal.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptLabResultEvalCriteriaTest {

    private SupportResponse responseWithNextAction(String nextAction) {
        return new SupportResponse(
                "",
                SupportResponse.Category.ORDER,
                SupportResponse.Urgency.NORMAL,
                nextAction, List.of(),
                false
        );
    }

    private SupportResponse responseWithSummary(String summary) {
        return new SupportResponse(
                summary,
                SupportResponse.Category.ORDER,
                SupportResponse.Urgency.NORMAL,
                "상담사 연결", List.of(),
                false
        );
    }

    @Test
    void evalCriteria_differentAllowedSet_producesDistinctResult() {
        var results = List.of(
                responseWithNextAction("주문 상태 조회 요청"),
                responseWithNextAction("추가 정보 확인")
        );
        var noViolationKeywords = List.<String>of();
        var noNeededInfoRequirements = Map.<String, String>of();
        EvalCriteria narrowCriteria = new EvalCriteria(
                Set.of("상담사 연결"),
                noViolationKeywords,
                noNeededInfoRequirements
        );

        var result = PromptLabResult.from(results, narrowCriteria);

        assertThat(result.nextActionComplianceRate()).isEqualTo(0.0);
    }

    @Test
    void evalCriteria_emptyViolationKeywords_rateIsZero() {
        var results = List.of(
                responseWithSummary("쿠팡이츠 언급해도 위반 키워드 없으면 0")
        );
        var noViolationKeywords = List.<String>of();
        var noNeededInfoRequirements = Map.<String, String>of();
        EvalCriteria noViolationCriteria = new EvalCriteria(
                Set.of("상담사 연결"),
                noViolationKeywords,
                noNeededInfoRequirements
        );

        var result = PromptLabResult.from(results, noViolationCriteria);

        assertThat(result.prohibitionViolationRate()).isEqualTo(0.0);
    }
}
