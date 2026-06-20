package com.baedal.support.guardrail;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Round 5 — 상담원 전환 트리거 판별기.
 *
 * <p>우선순위 EXPLICIT → LEGAL → ANGER 순으로 본다. 컨트롤러가 <b>LLM 호출 전에</b> 이걸 먼저 돌려,
 * 전환이 걸리면 모델을 부르지 않고 연결 안내를 바로 돌려준다(수십 ms, 토큰 0).</p>
 *
 * <p>우선순위가 중요한 이유: "너무 화나서 소비자원에 신고할 거예요"는 분노 단어와 법적 단어를
 * 동시에 가진다. ANGER를 먼저 보면 이걸 단순 감정 폭발로 분류해 더 중요한 법적 신호를 잃는다.
 * 그래서 더 분명하고 위험한 신호(명시 요청 → 법적 이슈)를 앞에 둔다.</p>
 *
 * <p>규칙 기반의 한계(띄어쓰기 우회·완곡한 분노·영문 비정형 미탐지)는 docs/5주차/03에 관찰로 남긴다.</p>
 */
@Component
public class HandoffDetector {

    static final String SUPPORT_PHONE = "1600-0987";

    /** 명시적 상담원 요청: 상담원/상담사 + 연결·전환 동사. */
    private static final Pattern EXPLICIT = Pattern.compile(
            "(상담원|상담사|상담직원)[^\\n]{0,15}(연결|바꿔|바꾸|얘기|이야기|통화|직접|사람|넘겨)");

    /** 법적/규제 이슈: 소비자원·신고·고소·법적 대응. */
    private static final Pattern LEGAL = Pattern.compile(
            "(소비자원|소비자보호원|신고|고소|고발|법적|법으로|변호사|민원|공정위)");

    /** 강한 분노: 분노 표현 단어. */
    private static final Pattern ANGER = Pattern.compile(
            "(화나|화가|짜증|열받|열 받|빡치|미치겠|돌아버리|화딱지|답답해 죽|짜증나 죽|화나 죽)");

    public HandoffResult detect(String input) {
        if (input == null || input.isBlank()) {
            return HandoffResult.none();
        }
        if (EXPLICIT.matcher(input).find()) {
            return HandoffResult.of(Trigger.EXPLICIT_REQUEST,
                    "상담원 연결을 도와드리겠습니다. 연결이 지연되면 고객센터 " + SUPPORT_PHONE + "로 전화 주세요.");
        }
        if (LEGAL.matcher(input).find()) {
            return HandoffResult.of(Trigger.LEGAL_ISSUE,
                    "관련 사안은 전문 상담원이 직접 안내드리겠습니다. 고객센터 " + SUPPORT_PHONE + "로 연결해 드리겠습니다.");
        }
        if (ANGER.matcher(input).find()) {
            return HandoffResult.of(Trigger.HIGH_EMOTION,
                    "불편을 드려 죄송합니다. 상담원이 직접 도와드리겠습니다. 고객센터 " + SUPPORT_PHONE + "로 연결됩니다.");
        }
        return HandoffResult.none();
    }

    public enum Trigger { EXPLICIT_REQUEST, LEGAL_ISSUE, HIGH_EMOTION, NONE }

    /** 전환 판별 결과. handoff=true 면 trigger/message 가 채워진다. */
    public record HandoffResult(boolean handoff, Trigger trigger, String message) {
        static HandoffResult none() {
            return new HandoffResult(false, Trigger.NONE, null);
        }

        static HandoffResult of(Trigger trigger, String message) {
            return new HandoffResult(true, trigger, message);
        }
    }
}
