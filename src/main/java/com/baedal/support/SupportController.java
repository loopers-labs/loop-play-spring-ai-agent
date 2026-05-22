package com.baedal.support;

import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;

    public SupportController(ChatClient.Builder builder, PerformanceLoggingAdvisor advisor) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(advisor)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@Valid @RequestBody ChatRequest req) {
        return chatClient.prompt()
                .user(req.message())
                .call()
                .entity(SupportResponse.class);
    }
}
