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

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse response = chain.nextCall(request);
        long elapsedMs = System.currentTimeMillis() - start;

        var chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                log.info("LLM 호출 완료 — {}ms | 입력 토큰: {} | 출력 토큰: {} | 총 토큰: {}",
                        elapsedMs,
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens());
            } else {
                log.info("[LLM] elapsed={}ms  (token usage unavailable)", elapsedMs);
            }
        } else {
            log.info("LLM 호출 완료 — {}ms (metadata 없음)", elapsedMs);
        }

        return response;
    }
}
