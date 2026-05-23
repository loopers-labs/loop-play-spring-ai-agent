package com.baedal.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class SupportService {

    /** Structured Output(JSON 12필드) 용. `BaedalPrompt.SYSTEM_PROMPT` 적용. */
    private final ChatClient structuredChatClient;

    /** Streaming(자유 텍스트) 용. `BaedalPrompt.STREAMING_PROMPT` 적용. */
    private final ChatClient streamingChatClient;

    private final ObjectMapper objectMapper;

    public SupportService(
            ChatClient.Builder structuredBuilder,
            ChatClient.Builder streamingBuilder,
            PerformanceLoggingAdvisor performanceAdvisor,
            ObjectMapper objectMapper
    ) {
        this.structuredChatClient = structuredBuilder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .build();
        this.streamingChatClient = streamingBuilder
                .defaultSystem(BaedalPrompt.STREAMING_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .build();
        this.objectMapper = objectMapper;
    }

    /** 동기 호출 — Structured Output(JSON 12필드)으로 응답을 받는다. */
    public SupportResponse generateSupportResponse(String message) {
        return structuredChatClient
                .prompt()
                .user(message)
                .call()
                .entity(SupportResponse.class);
    }

    /**
     * 스트리밍 호출 — 고객에게 보일 자연어 응답만 토큰 단위로 흘려보낸다.
     * `STREAMING_PROMPT` 가 JSON 구조를 금지하므로 사용자 화면에 노출 가능한 자연어 한 단락만 옴.
     */
    public Flux<String> streamSupportResponse(String message) {
        return streamingChatClient
                .prompt()
                .user(message)
                .stream()
                .content();
    }

    /**
     * 동기 자유 텍스트 호출 — streaming 과 동일한 `STREAMING_PROMPT` 적용.
     * `/api/v1/chat` 에서 사용. 동기 vs streaming 의 *순수* 차이 비교를 위해 같은 prompt 공유.
     */
    public String chat(String message) {
        return streamingChatClient
                .prompt()
                .user(message)
                .call()
                .content();
    }

    /**
     * 자유 텍스트 streaming + 마지막에 분류 메타데이터(SupportResponse) 한 번에 전송.
     *
     * SSE event type 으로 청크 종류 구분:
     *   - `event: token` — `STREAMING_PROMPT` 자연어 응답 토큰
     *   - `event: meta`  — streaming 종료 후 `SYSTEM_PROMPT` 동기 호출로 받은 12필드 JSON
     *
     * Trade-off: LLM 호출 2회 (streaming 1회 + structured 1회) → 비용 ×2.
     * 정확도 우선·UX 우선 모두 만족하지만 비용 trade-off 인지하고 사용.
     */
    public Flux<ServerSentEvent<String>> streamSupportWithMetadata(String message) {
        Flux<ServerSentEvent<String>> tokens = streamingChatClient
                .prompt()
                .user(message)
                .stream()
                .content()
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(chunk)
                        .build());

        // streaming 종료 후 같은 메시지를 SYSTEM_PROMPT 로 다시 호출 → 12필드 JSON 직렬화 후 meta event
        Mono<ServerSentEvent<String>> meta = Mono.fromCallable(
                () -> {
                    SupportResponse classified = generateSupportResponse(message);
                    return ServerSentEvent.<String>builder()
                            .event("meta")
                            .data(objectMapper.writeValueAsString(classified))
                            .build();
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(tokens, meta);
    }
}
