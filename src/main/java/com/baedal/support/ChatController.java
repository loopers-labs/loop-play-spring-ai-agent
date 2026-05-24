package com.baedal.support;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatController {

    @Qualifier("plainChatClient")
    private final ChatClient plainChatClient;

    @PostMapping
    public String chat(@Valid @RequestBody ChatRequest request) {
        return plainChatClient.prompt()
                .user(request.message())
                .call()
                .content();
    }
}
