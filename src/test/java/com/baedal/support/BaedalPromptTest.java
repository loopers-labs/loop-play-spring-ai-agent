package com.baedal.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaedalPromptTest {

    @Test
    void systemPrompt_locksNeutralBusinessTone() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
                .contains("사무적이고 단정한 존댓말")
                .contains("감탄, 과한 공감, 애교체, 이모지, 물결표")
                .contains("확인된 사실과 필요한 다음 단계만 말합니다")
                .contains("고객 진술 기준")
                .doesNotContain("고객님 말씀에 따르면")
                .doesNotContain("안내드린다");
    }
}
