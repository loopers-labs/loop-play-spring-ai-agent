package com.baedal.support.guardrail;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 5주차 — 민감 정보 마스킹 유틸리티.
 * <p>
 * LLM 또는 Tool이 실수로 출력에 노출할 수 있는 전화번호/이메일/주소를
 * 패턴 기반으로 마스킹한다. OutputGuardrailAdvisor에서 사용.
 *
 * <h3>원칙</h3>
 * <ul>
 *     <li><b>선제 방어</b>: 시스템 프롬프트로 "노출 금지"를 주어도 LLM은 확률적으로 새어나갈 수 있다.</li>
 *     <li><b>마스킹은 대체</b>, 제거가 아니다 — 응답 맥락은 유지하되 값만 가린다.</li>
 *     <li><b>과잉 마스킹 주의</b>: 주문번호 {@code 2024-1234} 같은 합법적 숫자까지 가리면 상담이 망가진다.</li>
 * </ul>
 */
@Component
public class SensitiveDataMasker {

    /** 한국 휴대전화: 010-1234-5678, 010 1234 5678, 01012345678 */
    private static final Pattern PHONE_KR = Pattern.compile(
            "01[016789][\\s-]?\\d{3,4}[\\s-]?\\d{4}");

    /** 이메일 */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 도로명 주소 — 매우 대략적 탐지.
     * "서울시 강남구 역삼동 123-45" 같은 형태를 잡는다. 완벽하지 않으므로 마스킹만 적용한다.
     */
    private static final Pattern ROAD_ADDRESS = Pattern.compile(
            "(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충청|전라|경상|제주)" +
                    // '시' 추가: '서울시'(지역+시) 형태 보강. 단독 '시'가 없으면 '서울시 강남구…'를 놓친다.
                    "(?:특별시|광역시|특별자치시|특별자치도|도|시)?\\s*" +
                    "[가-힣]+(?:시|군|구)\\s+[가-힣0-9\\-\\s]{2,30}(?:동|읍|면|로|길)\\s*\\d+(?:-\\d+)?");

    /**
     * 텍스트 내 민감 정보를 찾아 마스킹한 새 문자열을 반환한다.
     * 원본은 변경되지 않는다.
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        masked = maskPhone(masked);
        masked = maskEmail(masked);
        masked = maskAddress(masked);
        return masked;
    }

    /**
     * 뒷 4자리만 남기고 가운데를 *로. 010-1234-5678 → 010-****-5678
     * appendReplacement + quoteReplacement로 안전하게 순회 치환한다.
     */
    private String maskPhone(String text) {
        Matcher m = PHONE_KR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", ""); // 형식 무관 숫자만: 010-1234-5678 / 010 1234 5678 → 0101234567...
            String prefix = digits.substring(0, 3);            // 앞 3자리 보존(010·011·016… 왜곡 방지)
            String last4 = digits.substring(digits.length() - 4);
            m.appendReplacement(sb, Matcher.quoteReplacement(prefix + "-****-" + last4));
        }
        m.appendTail(sb);
        return sb.toString();
        // 한계: 국제표기(+82 10-…)는 앞 0이 빠져 PHONE_KR이 못 잡음 → findings_quest2.md 실패관찰 참조.
    }

    /**
     * name@domain.com → n***@domain.com
     * '@' 앞 로컬 파트는 첫 글자만 남기고 "***", 길이가 1 이하면 전체를 "*"로.
     */
    private String maskEmail(String text) {
        Matcher m = EMAIL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String email = m.group();
            int at = email.indexOf('@');
            String local = email.substring(0, at);
            String domain = email.substring(at);                  // "@woowahan.com"
            String maskedLocal = local.length() <= 1 ? "*" : local.charAt(0) + "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(maskedLocal + domain));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 주소는 매칭 구간 전체를 "[주소 비공개]"로 대체한다.
     */
    private String maskAddress(String text) {
        // 주소는 구성요소가 많아 값만 가리기 어렵다 → 매칭 구간을 통째로 대체.
        return ROAD_ADDRESS.matcher(text).replaceAll("[주소 비공개]");
    }

    /**
     * 마스킹이 실제로 일어났는지 여부.
     * 로깅/감사 용도로 유용하다.
     */
    public boolean containsSensitive(String text) {
        if (text == null) return false;
        return PHONE_KR.matcher(text).find()
                || EMAIL.matcher(text).find()
                || ROAD_ADDRESS.matcher(text).find();
    }
}
