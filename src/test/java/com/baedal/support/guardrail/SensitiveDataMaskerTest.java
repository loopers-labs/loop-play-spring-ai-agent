package com.baedal.support.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 5 — 2단계: SensitiveDataMasker 결정적 동작 검증.
 * <p>
 * 시나리오 응답은 LLM이 민감정보를 재현하는지에 따라 비결정적이므로,
 * 마스킹 정규식 자체의 동작은 단위 테스트로 증명한다(quest "원본 vs 마스킹 대조").
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    @DisplayName("전화번호: 010-1234-5678 → 010-****-5678")
    void maskPhone() {
        assertEquals("010-****-5678", masker.mask("010-1234-5678"));
        assertEquals("연락처는 010-****-5678 입니다", masker.mask("연락처는 010-1234-5678 입니다"));
        assertEquals("010-****-5678", masker.mask("01012345678")); // 구분자 없는 형태도
    }

    @Test
    @DisplayName("이메일: len@woowahan.com → l***@woowahan.com / 로컬 1자는 전체 *")
    void maskEmail() {
        assertEquals("l***@woowahan.com", masker.mask("len@woowahan.com"));
        assertEquals("*@b.co", masker.mask("a@b.co"));
    }

    @Test
    @DisplayName("주소: 서울시 강남구 역삼동 123-45 → [주소 비공개]")
    void maskAddress() {
        assertEquals("[주소 비공개]", masker.mask("서울시 강남구 역삼동 123-45"));
    }

    @Test
    @DisplayName("복합: 전화/이메일/주소 동시 마스킹")
    void maskMulti() {
        String in = "번호 010-1111-2222 메일 a@b.co 주소 서울시 강남구 역삼동 12";
        String out = masker.mask(in);
        assertTrue(out.contains("010-****-2222"), out);
        assertTrue(out.contains("*@b.co"), out);
        assertTrue(out.contains("[주소 비공개]"), out);
        assertFalse(out.contains("010-1111-2222"));
    }

    @Test
    @DisplayName("과잉 마스킹 방지: 주문번호 2024-1234 는 전화번호로 오탐되지 않는다")
    void orderNumberNotMasked() {
        assertEquals("2024-1234 주문 어디쯤?", masker.mask("2024-1234 주문 어디쯤?"));
        assertFalse(masker.containsSensitive("2024-1234"));
    }

    @Test
    @DisplayName("실패 관찰: ROAD_ADDRESS가 '종로3가' 형태를 놓친다(미흡한 마스킹)")
    void missedAddressPattern() {
        // ROAD_ADDRESS는 끝을 (동|읍|면|로|길)\\d+ 로 가정하므로 "종로3가 102"의 '가'는 매칭 못 함.
        String missed = "서울 종로구 종로3가 102";
        assertEquals(missed, masker.mask(missed), "현재 정규식은 이 주소를 마스킹하지 못한다(놓침)");
        assertFalse(masker.containsSensitive(missed));
    }
}
