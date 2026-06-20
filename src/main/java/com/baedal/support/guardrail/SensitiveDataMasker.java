package com.baedal.support.guardrail;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Round 5 — 출력 단 민감 정보 마스킹.
 *
 * <p>제거(삭제)가 아니라 대체(masking)를 한다. 자리표시는 남겨 두어야 상담원이
 * "전화번호가 있었다"는 맥락을 잃지 않고, 응답 흐름도 자연스럽게 유지된다.</p>
 *
 * <p>경계 설계 메모(실패 관찰은 docs/5주차/02):
 * <ul>
 *   <li>{@link #PHONE}는 휴대폰 접두 {@code 01[016789]}로 시작할 때만 잡는다. 주문번호 {@code 2024-1234}는
 *       접두가 다르므로 오탐(과잉 마스킹)되지 않는다.</li>
 *   <li>{@link #ADDRESS}는 "...구/군/시 + (동·읍·면) + 번지" 지번 형태만 잡는다.
 *       "종로3가"처럼 동 이름에 숫자가 끼거나 "~가/~로" 도로명은 놓친다(미흡 마스킹). 보완안은 문서에.</li>
 * </ul>
 */
@Component
public class SensitiveDataMasker {

    static final String ADDRESS_PLACEHOLDER = "[주소 비공개]";

    /** 휴대폰: 010-1234-5678 / 01012345678 → 010-****-5678 (가운데 묶음만 가림). */
    private static final Pattern PHONE =
            Pattern.compile("(01[016789])[-\\s]?(\\d{3,4})[-\\s]?(\\d{4})");

    /** 이메일: len@woowahan.com → l***@woowahan.com (로컬 첫 글자만 남김). */
    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    /** 지번 주소: 서울시 강남구 역삼동 123-45 → [주소 비공개]. (도로명/~가 동은 의도적 미커버) */
    private static final Pattern ADDRESS = Pattern.compile(
            "(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)"
                    + "(특별시|광역시|특별자치시|특별자치도|도|시)?\\s*"
                    + "[가-힣]{2,}(시|군|구)\\s*"
                    + "[가-힣]{2,}(동|읍|면)\\s*\\d[\\d-]*");

    public String maskPhone(String text) {
        if (text == null) {
            return null;
        }
        return PHONE.matcher(text).replaceAll("$1-****-$3");
    }

    public String maskEmail(String text) {
        if (text == null) {
            return null;
        }
        return EMAIL.matcher(text).replaceAll("$1***$2");
    }

    public String maskAddress(String text) {
        if (text == null) {
            return null;
        }
        return ADDRESS.matcher(text).replaceAll(Matcher.quoteReplacement(ADDRESS_PLACEHOLDER));
    }

    /** 세 가지를 모두 적용한 최종 마스킹 결과. */
    public String mask(String text) {
        return maskAddress(maskEmail(maskPhone(text)));
    }

    /** 응답에 마스킹 대상이 하나라도 있는지. OutputGuardrail이 치환 여부를 결정할 때 쓴다. */
    public boolean containsSensitive(String text) {
        if (text == null) {
            return false;
        }
        return PHONE.matcher(text).find()
                || EMAIL.matcher(text).find()
                || ADDRESS.matcher(text).find();
    }
}
