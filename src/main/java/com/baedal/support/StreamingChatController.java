package com.baedal.support;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat/stream")
public class StreamingChatController {

    private final ChatClient.Builder builder;

    @PostMapping(produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chatStream(@RequestBody ChatRequest req) {
        return builder
//                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .build()
                .prompt()
                .system(req.systemPrompt())
                .user(req.message())
                .stream()
                .content();
    }
}
