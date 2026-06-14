package com.baedal.support.guardrail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveDataMasker 단위 테스트.
 * <p>
 * 전화번호/이메일/주소 마스킹 형식과, "과잉 마스킹"(주문번호 오탐)·"미흡 마스킹"(주소 미탐)의
 * 경계를 회귀 가드로 고정한다. 마스킹 형식 문자열은 2단계 시나리오 리포트의 기대값과 일치해야 한다.
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @ParameterizedTest
    @ValueSource(strings = {
            "제 번호 010-1234-5678로 환불 안내 받을 수 있나요?",  // 하이픈
            "제 번호 010 1234 5678로 환불 안내 받을 수 있나요?",  // 공백
            "제 번호 01012345678로 환불 안내 받을 수 있나요?"     // 구분자 없음
    })
    void 전화번호는_뒤4자리만_남기고_마스킹한다(String text) {
        var result = masker.mask(text);

        assertThat(result).contains("010-****-5678");
        assertThat(result).doesNotContain("1234-5678");
    }

    @Test
    void 이메일은_로컬파트_첫글자만_남기고_마스킹한다() {
        var text = "제 이메일은 nickname@test.com 인데 알림은 어떻게 받나요?";

        var result = masker.mask(text);

        assertThat(result).contains("n***@test.com");
        assertThat(result).doesNotContain("nickname@test.com");
    }

    @Test
    void 로컬파트가_한글자면_전체를_별표로_마스킹한다() {
        var text = "메일 a@b.co 로 보내주세요";

        var result = masker.mask(text);

        assertThat(result).contains("*@b.co");
    }

    @Test
    void 주소는_전체를_비공개로_치환한다() {
        var text = "배달 주소는 서울시 강남구 역삼동 123-45인데 변경 가능해요?";

        var result = masker.mask(text);

        assertThat(result).contains("[주소 비공개]");
        assertThat(result).doesNotContain("역삼동 123-45");
    }

    @Test
    void 번호_이메일_주소를_동시에_마스킹한다() {
        var text = "내 번호 010-1111-2222 / 메일 a@b.co / 서울시 강남구 역삼동 12 저장돼 있어요?";

        var result = masker.mask(text);

        assertThat(result).contains("010-****-2222");
        assertThat(result).contains("*@b.co");
        assertThat(result).contains("[주소 비공개]");
    }

    @Test
    void 주문번호는_전화번호로_오탐하지_않는다() {
        var orderNumberQuery = "2024-1234 주문 어디쯤?";

        var containsSensitive = masker.containsSensitive(orderNumberQuery);
        var masked = masker.mask(orderNumberQuery);

        assertThat(containsSensitive).isFalse();
        assertThat(masked).isEqualTo(orderNumberQuery);
    }

    @Test
    void 옛지번_가_접미어_주소는_패턴이_놓친다() {
        var missedAddress = "서울 종로구 종로3가 102";

        var containsSensitive = masker.containsSensitive(missedAddress);

        assertThat(containsSensitive).isFalse();
    }
}
