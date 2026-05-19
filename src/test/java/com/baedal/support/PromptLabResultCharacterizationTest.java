package com.baedal.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PromptLabResultCharacterizationTest {

    private static final EvalCriteria BAEDAL_CRITERIA = BaedalPrompt.EVAL_CRITERIA;

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

    private SupportResponse responseWithNeededInfo(String nextAction, List<String> neededInfo) {
        return new SupportResponse(
                "",
                SupportResponse.Category.ORDER,
                SupportResponse.Urgency.NORMAL,
                nextAction, neededInfo,
                false
        );
    }

    // --- nextActionComplianceRate ---

    @Test
    void allAllowedNextActions_complianceRateIsOne() {
        var results = List.of(
                responseWithNextAction("주문 상태 조회 요청"),
                responseWithNextAction("추가 정보 확인"),
                responseWithNextAction("상담사 연결")
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.nextActionComplianceRate()).isEqualTo(1.0);
    }

    @Test
    void noAllowedNextActions_complianceRateIsZero() {
        var results = List.of(
                responseWithNextAction("알 수 없음"),
                responseWithNextAction("임의 값")
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.nextActionComplianceRate()).isEqualTo(0.0);
    }

    @Test
    void partialAllowedNextActions_complianceRateIsHalf() {
        var results = List.of(
                responseWithNextAction("주문 상태 조회 요청"),
                responseWithNextAction("허용되지 않는 값")
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.nextActionComplianceRate()).isCloseTo(0.5, within(0.001));
    }

    // --- prohibitionViolationRate --- 금지 키워드가 포함된 응답의 비율: 1.0은 모두 포함, 0.0은 모두 미포함

    @Test
    void noViolationKeywords_violationRateIsZero() {
        var results = List.of(
                responseWithSummary("문제없는 요약입니다."),
                responseWithSummary("친절하게 안내드렸습니다.")
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.prohibitionViolationRate()).isEqualTo(0.0);
    }

    @Test
    void allSummariesContainViolationKeyword_violationRateIsOne() {
        var results = List.of(
                responseWithSummary("쿠팡이츠에서도 이용하세요."),
                responseWithSummary("환불해드리겠습니다.")
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.prohibitionViolationRate()).isEqualTo(1.0);
    }

    @Test
    void halfSummariesContainViolationKeyword_violationRateIsHalf() {
        var results = List.of(
                responseWithSummary("요기요 앱에서 확인하세요."),
                responseWithSummary("문제없는 정상 요약입니다.")
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.prohibitionViolationRate()).isCloseTo(0.5, within(0.001));
    }

    // --- neededInfoCompletenessRate ---

    @Test
    void orderQueryWithOrderNumber_completenessRateIsOne() {
        var results = List.of(
                responseWithNeededInfo("주문 상태 조회 요청", List.of("주문번호", "연락처"))
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.neededInfoCompletenessRate()).isEqualTo(1.0);
    }

    @Test
    void orderQueryWithoutOrderNumber_completenessRateIsZero() {
        var results = List.of(
                responseWithNeededInfo("주문 상태 조회 요청", List.of("연락처"))
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.neededInfoCompletenessRate()).isEqualTo(0.0);
    }

    @Test
    void noOrderQueryNextAction_completenessRateIsOne() {
        var results = List.of(
                responseWithNextAction("상담사 연결")
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.neededInfoCompletenessRate()).isEqualTo(1.0);
    }

    @Test
    void mixedOrderQueryResults_completenessRateIsHalf() {
        var results = List.of(
                responseWithNeededInfo("주문 상태 조회 요청", List.of("주문번호")),
                responseWithNeededInfo("주문 상태 조회 요청", List.of("연락처"))
        );

        var result = PromptLabResult.from(results, BAEDAL_CRITERIA);

        assertThat(result.neededInfoCompletenessRate()).isCloseTo(0.5, within(0.001));
    }
}
