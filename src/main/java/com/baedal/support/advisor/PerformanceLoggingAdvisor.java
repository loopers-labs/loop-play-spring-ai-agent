package com.baedal.support.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * LLM 호출의 응답 시간·토큰 사용량을 로깅하는 Advisor.
 * 4단계 Observability — 운영팀이 토큰 비용·응답 시간 추세를 관찰할 수 있게 한다.
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
        long start = System.currentTimeMillis();

        // [요청] — 1차 LLM 호출 시점의 메시지 개수 + 최근 user 메시지 로깅
        if (log.isInfoEnabled() && request.prompt() != null) {
            int msgCount = request.prompt().getInstructions().size();
            log.info("[LLM-REQ] messages={} userMessage='{}'",
                    msgCount,
                    truncate(extractLastUserContent(request), 100));
        }

        ChatClientResponse response = chain.nextCall(request);
        long elapsedMs = System.currentTimeMillis() - start;

        Usage usage = extractUsage(response);

        if (usage != null) {
            log.info("[LLM] elapsedMs={} inputTokens={} outputTokens={} totalTokens={}",
                    elapsedMs,
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens());
        } else {
            log.info("[LLM] elapsedMs={} (usage metadata unavailable)", elapsedMs);
        }

        return response;
    }

    /** ChatResponse/Metadata/Usage 모두 null 가능 — 방어적 추출. */
    private Usage extractUsage(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) return null;
        if (response.chatResponse().getMetadata() == null) return null;
        return response.chatResponse().getMetadata().getUsage();
    }

    private String extractLastUserContent(ChatClientRequest request) {
        if (request.prompt() == null) return "";
        var instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            var msg = instructions.get(i);
            if (msg.getMessageType().name().equals("USER")) {
                return msg.getText();
            }
        }
        return "";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
