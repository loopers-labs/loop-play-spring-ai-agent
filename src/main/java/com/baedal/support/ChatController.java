package com.baedal.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder, PerformanceLoggingAdvisor performanceAdvisor) {
        this.chatClient = builder
                .defaultAdvisors(performanceAdvisor)
                .build();
    }

    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        return chatClient
                .prompt()
                .user(request.message())
                .call()
                .content();
    }
}
