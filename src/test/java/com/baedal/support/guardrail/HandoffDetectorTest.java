package com.baedal.support.guardrail;

import com.baedal.support.guardrail.HandoffDetector.HandoffDecision;
import com.baedal.support.guardrail.HandoffDetector.HandoffReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3단계 HandoffDetector.detect() 단위 검증 — 3종 트리거 + 우선순위 + 알려진 우회.
 * detect는 순수 함수라 Spring 컨텍스트 없이 직접 호출한다.
 */
class HandoffDetectorTest {

    private final HandoffDetector detector = new HandoffDetector();

    @Test
    @DisplayName("명시적 요청 → EXPLICIT_REQUEST + 연결번호 포함")
    void explicit() {
        HandoffDecision d = detector.detect("상담원이랑 직접 얘기하고 싶어요");
        assertTrue(d.handoff());
        assertEquals(HandoffReason.EXPLICIT_REQUEST, d.reason());
        assertTrue(d.message().contains("1600-0987"));
    }

    @Test
    @DisplayName("분노+법적 동시 → LEGAL_ISSUE (우선순위: ANGER보다 LEGAL 먼저)")
    void legalBeforeAnger() {
        // "너무 화나서"(ANGER) + "소비자원/신고"(LEGAL)가 동시 매치 → LEGAL이 우선이어야 함
        HandoffDecision d = detector.detect("이거 너무 화나서 소비자원에 신고할 거예요");
        assertTrue(d.handoff());
        assertEquals(HandoffReason.LEGAL_ISSUE, d.reason());
    }

    @Test
    @DisplayName("감정 고조 → HIGH_EMOTION")
    void anger() {
        HandoffDecision d = detector.detect("나 너무 화나는데 답답해 죽겠네");
        assertTrue(d.handoff());
        assertEquals(HandoffReason.HIGH_EMOTION, d.reason());
    }

    @Test
    @DisplayName("일반 문의 → none (전환 안 함)")
    void none() {
        assertFalse(detector.detect("주문 언제 도착해요?").handoff());
        assertFalse(detector.detect("").handoff());
    }

    @Test
    @DisplayName("정규화 보강: 띄어쓰기 '상 담 원'·구분기호 '상.담.원'은 이제 탐지됨")
    void normalized_spacedExplicit() {
        assertTrue(detector.detect("상 담 원 연결해 주세요").handoff());
        assertEquals(HandoffReason.EXPLICIT_REQUEST, detector.detect("상.담.원 바꿔주세요").reason());
    }

    @Test
    @DisplayName("정규화로도 못 막는 우회(회귀 고정): 동의어·다국어는 분류 LLM 영역")
    void stillBypass_synonymAndLang() {
        assertFalse(detector.detect("담당자분 한 분 연결해 주실 수 있나요").handoff());
        assertFalse(detector.detect("I need to speak to a real person").handoff());
    }
}
