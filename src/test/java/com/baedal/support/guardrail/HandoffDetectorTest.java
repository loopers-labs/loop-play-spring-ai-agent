package com.baedal.support.guardrail;

import com.baedal.support.guardrail.HandoffDetector.Trigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffDetectorTest {

    private final HandoffDetector detector = new HandoffDetector();

    @Test
    @DisplayName("S1 — 명시적 상담원 요청은 EXPLICIT_REQUEST, 연결번호 포함")
    void detectsExplicitRequest() {
        var result = detector.detect("상담원이랑 직접 얘기하고 싶어요");

        assertThat(result.handoff()).isTrue();
        assertThat(result.trigger()).isEqualTo(Trigger.EXPLICIT_REQUEST);
        assertThat(result.message()).contains("1600-0987");
    }

    @Test
    @DisplayName("S2 — 분노+법적 신호가 섞이면 LEGAL_ISSUE가 ANGER를 이긴다 (우선순위)")
    void legalBeatsAngerByPriority() {
        var result = detector.detect("이거 너무 화나서 소비자원에 신고할 거예요");

        assertThat(result.trigger()).isEqualTo(Trigger.LEGAL_ISSUE);
        assertThat(result.message()).contains("1600-0987");
    }

    @Test
    @DisplayName("S3 — 분노 표현만 있으면 HIGH_EMOTION")
    void detectsHighEmotion() {
        var result = detector.detect("나 너무 화나는데 답답해 죽겠네");

        assertThat(result.trigger()).isEqualTo(Trigger.HIGH_EMOTION);
        assertThat(result.message()).contains("1600-0987");
    }

    @Test
    @DisplayName("정상 문의는 전환되지 않는다")
    void normalQuestionNoHandoff() {
        assertThat(detector.detect("비 오는 날 배달 늦으면 보상 받나요?").handoff()).isFalse();
    }

    @Test
    @DisplayName("규칙 우회 — 띄어쓰기/완곡한 분노/영문 비정형은 현재 규칙이 놓친다")
    void rulebasedMissesBypassPhrases() {
        // 이 미탐지들을 테스트로 고정해 규칙 기반의 한계를 드러낸다 (보강 방안은 docs/5주차/03).
        assertThat(detector.detect("상 담 원 연결").handoff()).isFalse();          // 띄어쓰기 우회
        assertThat(detector.detect("진짜 너무너무 불편했습니다…").handoff()).isFalse(); // 완곡한 분노
        assertThat(detector.detect("agent plz").handoff()).isFalse();              // 영문 비정형
    }
}
