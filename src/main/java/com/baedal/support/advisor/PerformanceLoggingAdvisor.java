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
        // MessageChatMemoryAdvisor(order=10)가 먼저 동작하여 프롬프트에 이전 대화를 주입한 뒤
        // Performance가 마지막에 호출 시간을 집계한다.
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

        // [프롬프트 전문] — Memory 가 끼워 넣은 이전 대화를 눈으로 확인 (DEBUG, 운영 시 PII·토큰 때문에 끔)
        // 2회차부터 SYSTEM 뒤에 이전 USER/ASSISTANT 가 붙는다 → Memory 작동의 직접 증거
        if (log.isDebugEnabled() && request.prompt() != null) {
            var instructions = request.prompt().getInstructions();
            log.debug("[LLM-PROMPT] {} messages ↓", instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                var m = instructions.get(i);
                log.debug("  #{} [{}] {}", i, m.getMessageType(), truncate(m.getText(), 300));
            }
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
