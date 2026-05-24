package com.baedal.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PerformanceLoggingAdvisor implements CallAdvisor {

    @Override
    public String getName() {
        return "PerformanceLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        // 체인 바깥쪽에서 LLM 왕복 시간을 측정하기 위해 큰 값을 준다.
        return 100;
    }

    // TODO [4단계]: LLM 호출의 응답 시간과 토큰 사용량을 로깅하는 Advisor를 구현하라.
    //
    // 구현 힌트:
    // 1. 호출 전 System.currentTimeMillis()로 시작 시간을 기록한다.
    // 2. chain.nextCall(request)로 다음 Advisor/LLM을 호출한다.
    // 3. 응답에서 response.chatResponse().getMetadata().getUsage()로 토큰 정보를 꺼낸다.
    //    (chatResponse() 또는 getMetadata() 가 null일 수 있으므로 방어적으로 확인할 것)
    // 4. log.info()로 응답 시간(ms), 입력 토큰, 출력 토큰, 총 토큰을 출력한다.
    //
    // 구현 후 SupportController에서 .defaultAdvisors(performanceAdvisor)로 등록하라.
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse response = null;
        try {
            response = chain.nextCall(request);
            return response;
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            logUsage(elapsed, response);
        }
    }

    private void logUsage(long elapsed, ChatClientResponse response) {
        if (response == null) {
            log.info("[PerformanceLoggingAdvisor] elapsed={}ms (call failed)", elapsed);
            return;
        }
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            log.info("[PerformanceLoggingAdvisor] elapsed={}ms (token metadata unavailable)", elapsed);
            return;
        }
        var usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            log.info("[PerformanceLoggingAdvisor] elapsed={}ms (usage unavailable)", elapsed);
            return;
        }
        log.info("[PerformanceLoggingAdvisor] elapsed={}ms inputTokens={} outputTokens={} totalTokens={}",
                elapsed,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens());
    }
}
