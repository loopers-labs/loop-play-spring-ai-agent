package com.baedal.support.guardrail;

import com.baedal.support.observability.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputGuardrailAdvisorTest {

    private final OutputGuardrailAdvisor advisor = new OutputGuardrailAdvisor(
            new SensitiveDataMasker(), new AgentMetrics(new SimpleMeterRegistry()));

    @Test
    @DisplayName("시스템 프롬프트 마커가 새면 LEAK_FALLBACK으로 전체 치환한다")
    void replacesLeakWithFallback() {
        String leaked = "[역할]\n당신은 한국 배달 플랫폼의 고객 상담 에이전트입니다...";

        String result = advisor.transform(leaked);

        assertThat(result).doesNotContain("[역할]");
        assertThat(result).contains("안내해 드릴 수 없습니다");
    }

    @Test
    @DisplayName("민감 정보가 있으면 마스킹 결과로 치환한다")
    void masksSensitive() {
        String result = advisor.transform("환불 안내는 010-1234-5678로 보내드리겠습니다.");

        assertThat(result).contains("010-****-5678");
        assertThat(result).doesNotContain("010-1234-5678");
    }

    @Test
    @DisplayName("빈/공백 응답은 EMPTY_FALLBACK으로 치환한다")
    void replacesBlankWithFallback() {
        assertThat(advisor.transform("   ")).contains("답변을 생성하지 못했습니다");
    }

    @Test
    @DisplayName("정상 응답은 손대지 않는다 (false positive 없음)")
    void leavesCleanResponseUntouched() {
        String clean = "현재 배달원은 역삼역 사거리 부근에 있으며, 예상 도착은 오후 3시입니다.";

        assertThat(advisor.transform(clean)).isEqualTo(clean);
    }
}
