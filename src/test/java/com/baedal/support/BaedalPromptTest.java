package com.baedal.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BaedalPromptTest {

    @Test
    void system_prompt_should_contain_required_sections() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("[역할]")
            .contains("[규칙]")
            .contains("[금지]")
            .contains("[응답 포맷]");
    }

    @Test
    void system_prompt_should_prohibit_competitor_recommendations() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("타사 배달 앱");
    }

    @Test
    void system_prompt_should_prohibit_personal_info_exposure() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("개인정보");
    }

    @Test
    void system_prompt_should_prohibit_coupon_promises() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("쿠폰");
    }

    @Test
    void system_prompt_should_contain_tool_usage_rules() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("[Tool 사용 규칙]")
            .contains("getOrderDetail")
            .contains("getDeliveryStatus")
            .contains("cancelOrder");
    }
}