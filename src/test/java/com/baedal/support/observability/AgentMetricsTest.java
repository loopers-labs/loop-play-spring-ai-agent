package com.baedal.support.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);

    @Test
    void recordsRequestFallbackGuardrailHandoffToolAndTokenMetrics() {
        metrics.request();
        metrics.fallback();
        metrics.guardrailBlock("input", "PROMPT_INJECTION");
        metrics.handoff("HIGH_EMOTION");
        metrics.toolInvoke("getOrderDetail", "success");
        metrics.tokens("total", 42);

        assertThat(counter("baedal.agent.request.total")).isEqualTo(1.0);
        assertThat(counter("baedal.agent.fallback")).isEqualTo(1.0);
        assertThat(counter("baedal.agent.guardrail.block", "kind", "input", "reason", "PROMPT_INJECTION"))
                .isEqualTo(1.0);
        assertThat(counter("baedal.agent.handoff", "reason", "HIGH_EMOTION")).isEqualTo(1.0);
        assertThat(counter("baedal.agent.tool.invoke", "tool", "getOrderDetail", "outcome", "success"))
                .isEqualTo(1.0);
        assertThat(counter("baedal.agent.tokens", "type", "total")).isEqualTo(42.0);
    }

    @Test
    void recordsLlmLatencyTimer() {
        metrics.llmLatency(Duration.ofMillis(120));

        var timer = registry.find("baedal.agent.llm.latency").timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(120.0);
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
