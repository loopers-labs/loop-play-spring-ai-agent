package com.baedal.support.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 5주차 — 상담원 전환(Human Handoff) 트리거 탐지기.
 * <p>
 * 다음 신호 중 하나라도 감지되면 "상담원 연결" 안내로 응답을 전환한다.
 *
 * <h3>트리거</h3>
 * <ul>
 *     <li><b>명시적 요청</b>: "상담원", "사람이랑", "직원 바꿔줘"</li>
 *     <li><b>감정 고조</b>: 욕설/분노 표현, 반복되는 불만 키워드</li>
 *     <li><b>법적/금전 이슈</b>: "소송", "변호사", "언론", "신고"</li>
 * </ul>
 *
 * <h3>왜 규칙 기반인가</h3>
 * - 감정 분석을 LLM으로 돌릴 수도 있지만 비용/지연이 추가된다.
 * - 교육 단계에서는 "명시적 신호"를 규칙으로 먼저 잡고, 프로덕션에서는 감정 분류기로 보강한다.
 */
@Slf4j
@Component
public class HandoffDetector {

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

    /**
     * 상담원 전환 트리거 판별. 우선순위 EXPLICIT → LEGAL → ANGER 순으로 검사하고,
     * 매치되면 연결 번호 "1600-0987"을 포함한 안내 메시지로 handoff, 아니면 none().
     * (우선순위 근거는 round5/README.md 설계 결정 섹션 참조.)
     */
    public HandoffDecision detect(String input) {
        if (input == null || input.isBlank()) {
            return HandoffDecision.none();
        }
        // 2차 우회 측정 보강: 띄어쓰기("상 담 원")·구분기호("상.담.원")·비속어 분리("ㅅ ㅂ")를
        //   막기 위해 원문과 정규화본(구두점/공백 제거)을 모두 검사한다(1단계 InputGuardrail과 동형).
        //   단 동의어·완곡·다국어 우회는 정규화로 막히지 않는다(분류 LLM 영역).
        String norm = normalizeForCheck(input);
        // 우선순위: EXPLICIT → LEGAL → ANGER (분노+법적 겹칠 때 법적 대응 우선)
        if (matchesAny(input, EXPLICIT_PATTERNS) || matchesAny(norm, EXPLICIT_PATTERNS)) {
            return HandoffDecision.handoff(HandoffReason.EXPLICIT_REQUEST,
                    "네, 바로 상담원에게 연결해 드릴게요. 잠시만 기다려 주세요. (연결 번호: 1600-0987)");
        }
        if (matchesAny(input, LEGAL_PATTERNS) || matchesAny(norm, LEGAL_PATTERNS)) {
            return HandoffDecision.handoff(HandoffReason.LEGAL_ISSUE,
                    "법적·민원 관련 사안은 전문 상담원이 도와드려야 합니다. 상담원 연결을 진행할게요. (연결 번호: 1600-0987)");
        }
        if (matchesAny(input, ANGER_PATTERNS) || matchesAny(norm, ANGER_PATTERNS)) {
            return HandoffDecision.handoff(HandoffReason.HIGH_EMOTION,
                    "많이 불편하셨을 것 같아 정말 죄송합니다. 상담원이 직접 도와드릴 수 있도록 연결해 드릴게요. (연결 번호: 1600-0987)");
        }
        return HandoffDecision.none();
    }

    /** 우회 방어용 정규화 — 1단계 InputGuardrail과 동일(NFKC + 제로폭/구두점/공백 제거). */
    private static String normalizeForCheck(String input) {
        String n = Normalizer.normalize(input, Normalizer.Form.NFKC);
        n = n.replaceAll("\\p{Cf}", "");
        n = n.replaceAll("[\\p{P}\\p{Z}\\s]", "");
        return n;
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
