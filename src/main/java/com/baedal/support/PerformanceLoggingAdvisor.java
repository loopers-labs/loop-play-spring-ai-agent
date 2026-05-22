package com.baedal.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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
        return 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long startNanos = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            logSuccess(elapsedMs(startNanos), response);
            return response;
        } catch (RuntimeException e) {
            logFailure(elapsedMs(startNanos), e);
            throw e;
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void logSuccess(long elapsedMs, ChatClientResponse response) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse != null) {
            ChatResponseMetadata metadata = chatResponse.getMetadata();
            if (metadata != null) {
                Usage usage = metadata.getUsage();
                if (usage != null) {
                    promptTokens = usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens();
                    totalTokens = usage.getTotalTokens();
                }
            }
        }

        log.info("LLM call elapsedMs={} promptTokens={} completionTokens={} totalTokens={}",
                elapsedMs, promptTokens, completionTokens, totalTokens);
    }

    private void logFailure(long elapsedMs, RuntimeException e) {
        log.warn("LLM call failed elapsedMs={} type={} message={}",
                elapsedMs, e.getClass().getSimpleName(), e.getMessage());
    }
}
