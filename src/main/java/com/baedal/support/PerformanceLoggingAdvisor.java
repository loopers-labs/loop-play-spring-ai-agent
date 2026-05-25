package com.baedal.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 호출 시간과 토큰 사용량을 로깅하는 Advisor.
 * <p>
 * {@link PerCallObservationHandler}가 각 OllamaChatModel.internalCall() 호출에서
 * [LLM #N] 로그를 찍고 토큰 수를 누적한다.
 * 이 Advisor는 전체 왕복이 끝난 뒤 [PERF] 요약 로그를 출력한다.
 * <p>
 * 출력 로그 예시 (Tool Calling 발생 시):
 * <pre>
 * [LLM #1] elapsed=1200ms input=520 output=12   ← 1차 호출 (tool_call 응답)
 * [LLM #2] elapsed=950ms  input=610 output=43   ← 2차 호출 (ToolResponseMessage 포함)
 * [PERF]   elapsed=2200ms 총호출=2회 누적입력=1130 누적출력=55 누적합계=1185
 * </pre>
 */
@Slf4j
@Component
public class PerformanceLoggingAdvisor implements CallAdvisor {

    private static final int ORDER = 100;
    private static final long NANOS_PER_MS = 1_000_000;

    private final PerCallObservationHandler perCallHandler;

    public PerformanceLoggingAdvisor(PerCallObservationHandler perCallHandler) {
        this.perCallHandler = perCallHandler;
    }

    @Override
    public String getName() {
        return "PerformanceLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        perCallHandler.beginRequest();
        long startNano = System.nanoTime();

        try {
            ChatClientResponse response = chain.nextCall(request);
            long elapsedMs = (System.nanoTime() - startNano) / NANOS_PER_MS;
            logSummary(elapsedMs);
            return response;
        } catch (Exception ex) {
            long elapsedMs = (System.nanoTime() - startNano) / NANOS_PER_MS;
            log.warn("[PERF] LLM 호출 실패 — elapsed={}ms", elapsedMs, ex);
            throw ex;
        } finally {
            perCallHandler.endRequest();
        }
    }

    private void logSummary(long elapsedMs) {
        try {
            List<long[]> stats = perCallHandler.getCallStats();
            int calls = stats.size();
            long totalInput = stats.stream().mapToLong(s -> s[0]).sum();
            long totalOutput = stats.stream().mapToLong(s -> s[1]).sum();

            log.info("[PERF] elapsed={}ms 총호출={}회 누적입력={} 누적출력={} 누적합계={}",
                    elapsedMs, calls, totalInput, totalOutput, totalInput + totalOutput);
        } catch (Exception loggingEx) {
            log.warn("[PERF] 로깅 중 예외 발생 (응답은 정상 반환)", loggingEx);
        }
    }
}
