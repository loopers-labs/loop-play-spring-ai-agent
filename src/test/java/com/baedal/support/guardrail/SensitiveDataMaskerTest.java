package com.baedal.support.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    @DisplayName("휴대폰 가운데 묶음을 마스킹한다 (대시/무대시 모두)")
    void masksPhone() {
        assertThat(masker.maskPhone("환불 안내는 010-1234-5678로 드립니다"))
                .contains("010-****-5678");
        assertThat(masker.maskPhone("01012345678"))
                .isEqualTo("010-****-5678");
    }

    @Test
    @DisplayName("이메일 로컬의 첫 글자만 남기고 마스킹한다")
    void masksEmail() {
        assertThat(masker.maskEmail("제 메일은 len@woowahan.com 입니다"))
                .contains("l***@woowahan.com");
        assertThat(masker.maskEmail("a@b.co"))
                .isEqualTo("a***@b.co");
    }

    @Test
    @DisplayName("지번 주소를 [주소 비공개]로 치환한다")
    void masksAddress() {
        assertThat(masker.maskAddress("서울시 강남구 역삼동 123-45로 보내주세요"))
                .contains("[주소 비공개]")
                .doesNotContain("역삼동 123-45");
        assertThat(masker.maskAddress("서울시 강남구 역삼동 12"))
                .contains("[주소 비공개]");
    }

    @Test
    @DisplayName("과잉 마스킹 방지 — 주문번호 2024-1234는 휴대폰으로 오탐되지 않는다")
    void doesNotMaskOrderId() {
        String text = "2024-1234 주문 어디쯤?";
        assertThat(masker.maskPhone(text)).isEqualTo(text);
        assertThat(masker.containsSensitive(text)).isFalse();
    }

    @Test
    @DisplayName("미흡 마스킹 관찰 — '종로3가' 같은 ~가 동/도로명은 현재 패턴이 놓친다")
    void missesRoadAndGaAddress() {
        String text = "서울 종로구 종로3가 102";
        // 현재 ADDRESS 패턴은 (동·읍·면)만 잡아 '종로3가'를 놓친다. 이 실패를 테스트로 고정해 둔다.
        assertThat(masker.maskAddress(text)).isEqualTo(text);
        assertThat(masker.containsSensitive(text)).isFalse();
    }

    @Test
    @DisplayName("mask()는 전화·이메일·주소를 한 번에 적용한다")
    void masksAllAtOnce() {
        String text = "내 번호 010-1111-2222 / 메일 a@b.co / 서울시 강남구 역삼동 12";
        String masked = masker.mask(text);

        assertThat(masked).contains("010-****-2222");
        assertThat(masked).contains("a***@b.co");
        assertThat(masked).contains("[주소 비공개]");
        assertThat(masked).doesNotContain("010-1111-2222");
    }
}
