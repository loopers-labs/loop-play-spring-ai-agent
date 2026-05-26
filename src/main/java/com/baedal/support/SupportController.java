package com.baedal.support;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;

    public SupportController(ChatClient.Builder builder, PerformanceLoggingAdvisor performanceAdvisor) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@Valid @RequestBody ChatRequest req) {
        try {
            return chatClient
                    .prompt()
                    .user(req.message())
                    .call()
                    .entity(SupportResponse.class);
        } catch (Exception e) {
            log.error("LLM triage call failed", e);
            throw new SupportServiceException("고객 문의 처리 중 오류가 발생했습니다.", e);
        }
    }
}
