package com.baedal.support;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat/stream")
public class StreamingChatController {

    private final SupportService supportService;

    /**
     * SSE 응답:
     *   - `event: token` : 자연어 응답 토큰 (실시간 흐름)
     *   - `event: meta`  : 마지막에 12필드 분류 메타데이터 (한 번)
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatRequest req) {
        return supportService.streamSupportWithMetadata(req.message());
    }
}
