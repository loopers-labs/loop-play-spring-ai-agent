package com.baedal.support;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat/stream")
public class StreamingChatController {

    private final ChatClient.Builder builder;

    // TODO [3단계]: Streaming 응답 엔드포인트를 구현하라.
    //
    // 구현 힌트:
    // 1. BaedalPrompt.SYSTEM_PROMPT를 적용한다.
    // 2. .call() 대신 .stream()을 사용한다.
    // 3. .content()로 Flux<String>을 반환한다.
    //
    // 테스트:
    // curl -N -X POST http://localhost:8080/api/v1/chat/stream \
    //   -H "Content-Type: application/json" \
    //   -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'
    //
    // 글자가 한 글자씩 타이핑되듯 나타나면 성공입니다.
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
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
