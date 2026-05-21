package com.baedal.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

/**
 * LLM 호출 시간과 토큰 사용량을 로깅하는 Advisor.
 * <p>
 * Spring AI 1.0 GA 기준 {@link CallAdvisor} 시그니처:
 * <pre>{@code
 * ChatClientResponse adviseCall(ChatClientRequest, CallAdvisorChain);
 * }</pre>
 * Tool Calling이 적용된 호출도 이 Advisor 하나로 전체 왕복 시간이 측정된다
 * (Spring AI는 Tool 실행을 포함한 전체 루프가 끝난 뒤 최종 응답을 반환한다).
 */
@Slf4j
@Component
public class PerformanceLoggingAdvisor implements CallAdvisor {

    private static final int ORDER = 100;
    private static final long NANOS_PER_MS = 1_000_000;
    private static final long SLOW_RESPONSE_THRESHOLD_MS = 3000;

    @Override
    public String getName() {
        return "PerformanceLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        // 체인 바깥쪽에서 LLM 왕복 시간을 측정하기 위해 큰 값을 준다.
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long startNano = System.nanoTime();

        try {
            ChatClientResponse response = chain.nextCall(request);

            long elapsedMs = (System.nanoTime() - startNano) / NANOS_PER_MS;
            logSuccess(response, elapsedMs);

            return response;
        } catch (Exception ex) {
            long elapsedMs = (System.nanoTime() - startNano) / NANOS_PER_MS;
            log.warn("[PERF] LLM 호출 실패 — elapsed={}ms", elapsedMs, ex);
            throw ex;
        }
    }

    private void logSuccess(ChatClientResponse response, long elapsedMs) {
        try {
            var chatResponse = response.chatResponse();
            var usage = (chatResponse != null && chatResponse.getMetadata() != null)
                    ? chatResponse.getMetadata().getUsage()
                    : null;

            if (usage != null) {
                log.info("[PERF] elapsed={}ms input={} output={} total={}",
                        elapsedMs,
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens());
            } else {
                log.info("[PERF] elapsed={}ms input=- output=- total=-", elapsedMs);
            }

            if (elapsedMs > SLOW_RESPONSE_THRESHOLD_MS) {
                log.warn("[PERF] 응답 시간 임계값 초과 — elapsed={}ms", elapsedMs);
            }
        } catch (Exception loggingEx) {
            log.warn("[PERF] 로깅 중 예외 발생 (응답은 정상 반환)", loggingEx);
        }
    }
}
