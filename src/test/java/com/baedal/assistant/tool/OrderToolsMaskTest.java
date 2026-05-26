package com.baedal.assistant.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("OrderTools.maskOrderId: 운영 로그에 주문번호 평문 노출 방지")
class OrderToolsMaskTest {

    @Test
    @DisplayName("YYYY-XXXX 형식은 앞 4자리만 노출하고 뒤는 마스킹")
    void mask_standardFormat() {
        assertEquals("2024-***", OrderTools.maskOrderId("2024-1234"));
        assertEquals("2024-***", OrderTools.maskOrderId("2024-9999"));
    }

    @Test
    @DisplayName("null/blank은 'null' 문자열로 표기")
    void mask_nullOrBlank() {
        assertEquals("null", OrderTools.maskOrderId(null));
        assertEquals("null", OrderTools.maskOrderId(""));
        assertEquals("null", OrderTools.maskOrderId("   "));
    }

    @Test
    @DisplayName("4자 이하 짧은 입력은 통째로 마스킹")
    void mask_shortInput() {
        assertEquals("***", OrderTools.maskOrderId("12"));
        assertEquals("***", OrderTools.maskOrderId("2024"));
    }

    @Test
    @DisplayName("앞 4자리 외 문자는 노출되지 않는다 - 정규식 보장")
    void mask_doesNotLeakSuffix() {
        String masked = OrderTools.maskOrderId("2024-1234");
        assertEquals(true, masked.endsWith("***"));
        assertEquals(false, masked.contains("1234"));
    }
}
