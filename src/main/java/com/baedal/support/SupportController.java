package com.baedal.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;

    public SupportController(ChatClient.Builder builder,
                             PerformanceLoggingAdvisor performanceLoggingAdvisor) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceLoggingAdvisor)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req) {
        return chatClient
                .prompt()
                .user(req.message())
                .call()
                .entity(SupportResponse.class);
    }
}
