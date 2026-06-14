package com.baedal.support.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 5주차 — 상담원 전환(Human Handoff) 트리거 탐지기.
 * <p>
 * LLM 은 "도움이 되는" 쪽으로 학습돼 고객이 "사람과 얘기하고 싶다"고 해도 계속 응대하려 든다.
 * 이를 막으려 <b>LLM 호출 전</b> 컨트롤러/서비스에서 규칙 기반으로 전환 신호를 감지하고
 * 일관된 안내 문구(연결번호 {@link #CONTACT} 포함)로 즉시 응답한다.
 *
 * <h3>왜 LLM 호출 전인가</h3>
 * (1) LLM 에 문구 생성을 맡기면 매번 달라져 일관성 훼손, (2) "기다려 달라" 설득으로 회피해 분노 가중,
 * (3) 토큰·지연 낭비. 그래서 Advisor 체인 안이 아니라 컨트롤러에서 선검사한다.
 *
 * <h3>왜 EXPLICIT → LEGAL → ANGER 순인가</h3>
 * 명시적 요청은 가장 분명한 의사라 최우선. 법적/민원은 오답 시 리스크가 가장 커 분노보다 앞.
 * 분노를 먼저 두면 "소비자원에 신고할 거예요"(분노 표현 + 법적 키워드 동시)가 HIGH_EMOTION 으로
 * 분류돼 법적 사안 전용 응대를 놓친다 — 시나리오 2 가 이 우선순위의 직접 근거다.
 *
 * <h3>왜 규칙 기반인가 (한계)</h3>
 * 감정 분석을 LLM 으로 돌리면 비용·지연이 붙는다. 교육 단계는 명시적 신호를 규칙으로 먼저 잡고,
 * 띄어쓰기·완곡 표현 우회(아래 보고서)는 분류 LLM 으로 보강한다.
 */
@Slf4j
@Component
public class HandoffDetector {

    /** 상담원 연결 안내 번호 — 전환이 실제 동작 가능함을 보이려 모든 문구에 포함. */
    private static final String CONTACT = "1600-0987";

    /** 명시적 상담원 연결 요청 */
    private static final List<Pattern> EXPLICIT_PATTERNS = List.of(
            Pattern.compile("(?i)(상담원|상담\\s*직원|사람\\s*(이랑|한테|과|와)|직원\\s*(바꿔|연결)|human|agent)"),
            Pattern.compile("(?i)(전화\\s*연결|전화\\s*돌려|콜센터)")
    );

    /** 분노/강한 불만 — 한국어 대표 표현만 최소로 수록 */
    private static final List<Pattern> ANGER_PATTERNS = List.of(
            Pattern.compile("(화가\\s*(나|난)|너무\\s*화나|빡쳐|짜증|열받)"),
            Pattern.compile("(말이\\s*돼|미치겠|답답해\\s*죽)"),
            Pattern.compile("(f\\*+|sh\\*+|ㅅㅂ|ㅆㅂ|ㅂㅅ|ㅈㄹ)")  // 최소한의 비속어 필터
    );

    /** 법적/금전 이슈 — 즉시 상담원 연결 필요 */
    private static final List<Pattern> LEGAL_PATTERNS = List.of(
            Pattern.compile("(소송|변호사|고소|고발|언론|민원|신고)"),
            Pattern.compile("(소비자원|공정위|방통위|블랙컨슈머)")
    );

    private static final String MSG_EXPLICIT =
            "네, 바로 상담원에게 연결해 드릴게요. 잠시만 기다려 주세요. (상담원 연결: " + CONTACT + ")";
    private static final String MSG_LEGAL =
            "법적/민원 관련 사안은 전문 상담원이 도와드려야 합니다. 상담원 연결을 진행할게요. (상담원 연결: " + CONTACT + ")";
    private static final String MSG_ANGER =
            "많이 불편하셨을 것 같아 정말 죄송합니다. 상담원이 직접 도와드릴 수 있도록 연결해 드릴게요. (상담원 연결: " + CONTACT + ")";

    /**
     * 상담원 전환 트리거를 우선순위(EXPLICIT → LEGAL → ANGER)대로 판별한다.
     * 사과→공감→행동 순서가 정석이라 ANGER 문구는 "죄송합니다"를 먼저 둔다.
     */
    public HandoffDecision detect(String input) {
        if (input == null || input.isBlank()) {
            return HandoffDecision.none();
        }
        if (matchesAny(input, EXPLICIT_PATTERNS)) {
            return HandoffDecision.handoff(HandoffReason.EXPLICIT_REQUEST, MSG_EXPLICIT);
        }
        if (matchesAny(input, LEGAL_PATTERNS)) {
            return HandoffDecision.handoff(HandoffReason.LEGAL_ISSUE, MSG_LEGAL);
        }
        if (matchesAny(input, ANGER_PATTERNS)) {
            return HandoffDecision.handoff(HandoffReason.HIGH_EMOTION, MSG_ANGER);
        }
        return HandoffDecision.none();
    }

    private boolean matchesAny(String input, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    public enum HandoffReason {
        EXPLICIT_REQUEST, HIGH_EMOTION, LEGAL_ISSUE, REPEATED_FAILURE
    }

    public record HandoffDecision(boolean handoff, HandoffReason reason, String message) {
        public static HandoffDecision none() {
            return new HandoffDecision(false, null, null);
        }
        public static HandoffDecision handoff(HandoffReason reason, String message) {
            return new HandoffDecision(true, reason, message);
        }
    }
}
