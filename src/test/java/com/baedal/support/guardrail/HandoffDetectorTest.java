package com.baedal.support.guardrail;

import com.baedal.support.guardrail.HandoffDetector.HandoffDecision;
import com.baedal.support.guardrail.HandoffDetector.HandoffReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HandoffDetector.detect() 단위 테스트.
 * <p>
 * detect()는 LLM/Spring 컨텍스트 없이 순수 규칙 매칭만 검사하므로 직접 호출한다.
 * 트리거 우선순위는 EXPLICIT_REQUEST → LEGAL_ISSUE → HIGH_EMOTION 순이어야 하고,
 * 전환 응답 말미에는 연결 번호 "1600-0987" 이 포함되어 상담원 전환이 실제 동작 가능함을 보인다.
 */
class HandoffDetectorTest {

    private static final String HANDOFF_PHONE = "1600-0987";

    private final HandoffDetector detector = new HandoffDetector();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 빈_입력은_전환하지_않는다(String emptyInput) {
        var decision = detector.detect(emptyInput);

        assertThat(decision.handoff()).isFalse();
        assertThat(decision.reason()).isNull();
        assertThat(decision.message()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "상담원이랑 직접 얘기하고 싶어요",   // 상담원 + 사람이랑
            "직원 바꿔줘",                      // 직원 바꿔
            "agent plz"                        // 영문 비정형 — human|agent 패턴
    })
    void 명시적_요청은_EXPLICIT_REQUEST로_전환한다(String explicitInput) {
        var decision = detector.detect(explicitInput);

        assertThat(decision.handoff()).isTrue();
        assertThat(decision.reason()).isEqualTo(HandoffReason.EXPLICIT_REQUEST);
        assertThat(decision.message()).contains(HANDOFF_PHONE);
    }

    @Test
    void 법적_민원_신호는_LEGAL_ISSUE로_전환한다() {
        var legalInput = "소비자원에 신고할 거예요";

        var decision = detector.detect(legalInput);

        assertThat(decision.handoff()).isTrue();
        assertThat(decision.reason()).isEqualTo(HandoffReason.LEGAL_ISSUE);
        assertThat(decision.message()).contains(HANDOFF_PHONE);
    }

    @Test
    void 감정_고조_신호는_HIGH_EMOTION으로_전환한다() {
        var angerInput = "나 너무 화나는데 답답해 죽겠네";

        var decision = detector.detect(angerInput);

        assertThat(decision.handoff()).isTrue();
        assertThat(decision.reason()).isEqualTo(HandoffReason.HIGH_EMOTION);
        assertThat(decision.message()).contains(HANDOFF_PHONE);
    }

    @Test
    void 분노와_법적신호가_겹치면_LEGAL_ISSUE가_우선한다() {
        // "너무 화나"(ANGER) 와 "소비자원/신고"(LEGAL) 가 동시 매치되는 입력.
        // 우선순위가 LEGAL → ANGER 순이므로 LEGAL_ISSUE 가 나와야 한다.
        var angerWithLegalInput = "이거 너무 화나서 소비자원에 신고할 거예요";

        var decision = detector.detect(angerWithLegalInput);

        assertThat(decision.handoff()).isTrue();
        assertThat(decision.reason()).isEqualTo(HandoffReason.LEGAL_ISSUE);
    }

    @Test
    void 전환_신호가_없으면_전환하지_않는다() {
        var normalInput = "비 오는 날 배달 늦으면 보상 받나요?";

        var decision = detector.detect(normalInput);

        assertThat(decision.handoff()).isFalse();
        assertThat(decision.reason()).isNull();
        assertThat(decision.message()).isNull();
    }
}
