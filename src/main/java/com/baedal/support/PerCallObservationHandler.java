package com.baedal.support;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 각 OllamaChatModel.internalCall() 호출마다 토큰 수와 소요 시간을 로깅하는 ObservationHandler.
 * <p>
 * OllamaApi가 final 클래스라 CGLIB 프록시로 인터셉트할 수 없어 Micrometer Observation을 사용한다.
 * OllamaChatModel.internalCall()은 Tool Calling 발생 시 재귀 호출되며,
 * 각 재귀 호출마다 독립적인 ChatModelObservationContext가 생성된다.
 * <p>
 * 로그 출력 예시 (Tool Calling 발생 시):
 * <pre>
 * [LLM #1] elapsed=1200ms input=520 output=12   ← 1차 호출 (tool_call 응답)
 * [LLM #2] elapsed=950ms  input=610 output=43   ← 2차 호출 (ToolResponseMessage 포함)
 * [PERF]   elapsed=2200ms 총호출=2회 누적입력=1130 누적출력=55 누적합계=1185
 * </pre>
 */
@Slf4j
@Component
public class PerCallObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private static final long NANOS_PER_MS = 1_000_000;
    private static final long SLOW_CALL_THRESHOLD_MS = 3000;
    private static final String START_NANO_KEY = "perCallStartNano";

    // index 0: 입력 토큰, index 1: 출력 토큰, index 2: 소요 시간(ms)
    private final ThreadLocal<List<long[]>> callStats = ThreadLocal.withInitial(ArrayList::new);

    public PerCallObservationHandler(ObservationRegistry observationRegistry) {
        observationRegistry.observationConfig().observationHandler(this);
    }

    void beginRequest() {
        callStats.set(new ArrayList<>());
    }

    List<long[]> getCallStats() {
        return callStats.get();
    }

    void endRequest() {
        callStats.remove();
    }

    @Override
    public void onStart(ChatModelObservationContext context) {
        context.put(START_NANO_KEY, System.nanoTime());
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        List<long[]> stats = callStats.get();
        int callNum = stats.size() + 1;

        long elapsedMs = computeElapsedMs(context);
        long input = 0;
        long output = 0;

        if (context.getResponse() != null
                && context.getResponse().getMetadata() != null
                && context.getResponse().getMetadata().getUsage() != null) {
            var usage = context.getResponse().getMetadata().getUsage();
            input = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            output = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        }

        stats.add(new long[]{input, output, elapsedMs});
        log.info("[LLM #{}] elapsed={}ms input={} output={}", callNum, elapsedMs, input, output);

        if (elapsedMs > SLOW_CALL_THRESHOLD_MS) {
            log.warn("[LLM #{}] 단일 호출 임계값 초과 — elapsed={}ms", callNum, elapsedMs);
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    private long computeElapsedMs(ChatModelObservationContext context) {
        Object startNano = context.get(START_NANO_KEY);
        if (startNano instanceof Long start) {
            return (System.nanoTime() - start) / NANOS_PER_MS;
        }
        return -1;
    }
}
