package com.baedal.support.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final Counter requestTotal;
    private final Counter fallbackTotal;
    private final Timer llmLatency;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requestTotal = Counter.builder("baedal.agent.request.total")
                .description("Total assistant requests received by the controller")
                .register(registry);
        this.fallbackTotal = Counter.builder("baedal.agent.fallback")
                .description("Assistant requests answered by safe fallback")
                .register(registry);
        this.llmLatency = Timer.builder("baedal.agent.llm.latency")
                .description("Elapsed time spent inside the LLM advisor call")
                .register(registry);
    }

    public void request() {
        requestTotal.increment();
    }

    public void fallback() {
        fallbackTotal.increment();
    }

    public void llmLatency(Duration elapsed) {
        llmLatency.record(elapsed);
    }

    public void guardrailBlock(String kind, String reason) {
        Counter.builder("baedal.agent.guardrail.block")
                .description("Guardrail decisions that blocked or rewrote agent traffic")
                .tag("kind", kind)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void handoff(String reason) {
        Counter.builder("baedal.agent.handoff")
                .description("Requests handed off before LLM invocation")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void toolInvoke(String tool, String outcome) {
        Counter.builder("baedal.agent.tool.invoke")
                .description("Tool invocation count by tool and outcome")
                .tag("tool", tool)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void tokens(String type, Integer count) {
        if (count == null || count <= 0) {
            return;
        }
        Counter.builder("baedal.agent.tokens")
                .description("Token usage reported by the chat model")
                .tag("type", type)
                .register(registry)
                .increment(count);
    }
}
