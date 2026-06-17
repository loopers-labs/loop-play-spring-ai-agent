package com.baedal.support.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2단계 (B/C/D) 마스킹 로직 단위 검증 — 앱/Ollama 없이 SensitiveDataMasker.mask()를 직접 호출.
 * 과잉 마스킹(주문번호 오탐)과 알려진 우회(국제표기)를 회귀 테스트로 고정한다.
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    @DisplayName("전화: 010/011 앞3자리 보존 + 형식 통일")
    void maskPhone() {
        assertEquals("연락처 010-****-5678", masker.mask("연락처 010-1234-5678"));
        assertEquals("011-****-5678", masker.mask("011 1234 5678"));
    }

    @Test
    @DisplayName("과잉마스킹 방어: 주문번호 2024-1234는 전화로 오탐 안 됨")
    void maskPhone_noOverMask() {
        assertEquals("주문번호 2024-1234 어디쯤?", masker.mask("주문번호 2024-1234 어디쯤?"));
    }

    @Test
    @DisplayName("이메일: 첫 글자 + *** / 로컬 1글자면 전체 가림")
    void maskEmail() {
        assertEquals("l***@woowahan.com", masker.mask("len@woowahan.com"));
        assertEquals("*@b.co", masker.mask("a@b.co"));
    }

    @Test
    @DisplayName("주소: 매칭 구간 통째로 [주소 비공개] (서울시 보강 포함)")
    void maskAddress() {
        assertEquals("[주소 비공개]", masker.mask("서울시 강남구 역삼동 123-45"));
        assertEquals("[주소 비공개]", masker.mask("서울특별시 강남구 역삼동 123-45"));
    }

    @Test
    @DisplayName("알려진 한계(회귀 고정): 1글자 동명(우동)은 ROAD_ADDRESS가 놓침 (findings_quest2)")
    void knownLimit_singleCharDong() {
        assertEquals("부산시 해운대구 우동 1408", masker.mask("부산시 해운대구 우동 1408"));
    }

    @Test
    @DisplayName("알려진 우회(회귀 고정): 국제표기 +82 10-…은 현재 마스킹 안 됨 (findings_quest2)")
    void knownBypass_intlPhone() {
        assertEquals("+82 10-1234-5678", masker.mask("+82 10-1234-5678"));
    }
}
