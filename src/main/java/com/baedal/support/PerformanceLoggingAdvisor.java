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
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        long elapsed = System.nanoTime() - start;

        var chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            log.info("LLM 호출 완료 — {}ms | 입력 토큰: {} | 출력 토큰: {} | 총 토큰: {}",
                    elapsed,
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens());
        } else {
            log.info("LLM 호출 완료 — {}ms (metadata 없음)", elapsed);
        }

        return response;
    }
}
