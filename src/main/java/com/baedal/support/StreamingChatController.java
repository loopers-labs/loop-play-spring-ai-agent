package com.baedal.support;

import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/chat/stream")
public class StreamingChatController {

    private final ChatClient chatClient;

    public StreamingChatController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .build();
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@Valid @RequestBody ChatRequest req) {
        return chatClient.prompt()
                .user(req.message())
                .stream()
                .content()
                .onErrorResume(e -> Flux.just("[오류] 요청 처리 중 오류가 발생했습니다."));
    }
}
