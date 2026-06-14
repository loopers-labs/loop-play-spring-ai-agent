package com.baedal.support.guardrail;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 5주차 — 민감 정보 마스킹 유틸리티.
 * <p>
 * LLM 또는 Tool 이 실수로 출력에 노출할 수 있는 전화번호/이메일/주소를 패턴 기반으로 마스킹한다.
 * {@link OutputGuardrailAdvisor} 에서 사용.
 *
 * <h3>원칙</h3>
 * <ul>
 *     <li><b>선제 방어</b>: 시스템 프롬프트로 "노출 금지"를 줘도 LLM 은 확률적으로 새어나갈 수 있다.</li>
 *     <li><b>마스킹은 대체</b>, 제거가 아니다 — 응답 맥락은 유지하되 값만 가린다.</li>
 *     <li><b>과잉 마스킹 주의</b>: 주문번호 {@code 2024-1234} 같은 합법 숫자까지 가리면 상담이 망가진다.</li>
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
     * 도로명/지번 주소 — 매우 대략적 탐지. 완벽하지 않으므로 마스킹만 적용한다.
     * <p>
     * 스타터 정규식 확장: 광역시 접미 그룹에 맨 "시"를 추가했다. 스타터 원본은
     * {@code (특별시|광역시|특별자치시|도|특별자치도)?} 라 "서울특별시"·"서울 강남구"는 잡지만
     * 구어형 "서울시 강남구"(서울+시)를 놓쳤다 — quest 시나리오 3의 정확한 입력이 이 형태다.
     * (그래도 "서울 종로구 종로3가 102" 같은 지번형은 여전히 누락 — 보고서 보완 방안 참고.)
     */
    private static final Pattern ROAD_ADDRESS = Pattern.compile(
            "(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충청|전라|경상|제주)" +
                    "(?:특별자치시|특별자치도|특별시|광역시|도|시)?\\s*" +
                    "[가-힣]+(?:시|군|구)\\s+[가-힣0-9\\-\\s]{2,30}(?:동|읍|면|로|길)\\s*\\d+(?:-\\d+)?");

    private static final String ADDRESS_MASK = "[주소 비공개]";

    /**
     * 텍스트 내 민감 정보를 찾아 마스킹한 새 문자열을 반환한다. 원본은 변경되지 않는다.
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

    /** 뒷 4자리만 남기고 가운데를 *로. 010-1234-5678 → 010-****-5678 */
    private String maskPhone(String text) {
        Matcher m = PHONE_KR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            String replacement = digits.length() >= 7
                    ? digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4)
                    : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** name@domain.com → n***@domain.com. 로컬 파트가 1자 이하면 전체를 *로. */
    private String maskEmail(String text) {
        Matcher m = EMAIL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String email = m.group();
            int at = email.indexOf('@');
            String local = email.substring(0, at);
            String domain = email.substring(at); // '@' 포함
            String maskedLocal = local.length() <= 1 ? "*" : local.charAt(0) + "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(maskedLocal + domain));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 주소는 전체를 "[주소 비공개]"로 대체. */
    private String maskAddress(String text) {
        return ROAD_ADDRESS.matcher(text).replaceAll(Matcher.quoteReplacement(ADDRESS_MASK));
    }

    /**
     * 마스킹이 실제로 일어날지 여부. 로깅/감사용.
     */
    public boolean containsSensitive(String text) {
        if (text == null) return false;
        return PHONE_KR.matcher(text).find()
                || EMAIL.matcher(text).find()
                || ROAD_ADDRESS.matcher(text).find();
    }
}
