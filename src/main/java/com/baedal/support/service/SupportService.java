package com.baedal.support.service;

import com.baedal.support.advisor.PerformanceLoggingAdvisor;
import com.baedal.support.dto.SupportResponse;
import com.baedal.support.prompt.BaedalPrompt;
import com.baedal.support.tool.OrderTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 단일 ChatClient 로 동기·스트리밍·구조화 응답을 모두 처리한다.
 * <p>
 * Round 1 에서는 SYSTEM_PROMPT / STREAMING_PROMPT 두 ChatClient 로 분리했었지만,
 * Round 2 에서 다음 두 이유로 단일 prompt 로 통합:
 *  - SSE 옵션 A (token + meta) 가 분리의 원래 동기를 약화시킴
 *  - STREAMING_PROMPT 의 "확인 후 안내" 회피 표현이 Tool 호출 회피의 부작용으로 작용
 * <p>
 * 호출 메서드별 모드 분기:
 *  - generateSupportResponse  : .entity()  → Spring AI 가 JSON schema 첨부 → (B) 구조화 모드
 *  - chat / streamSupportResponse / streamSupportWithMetadata(token) : .content()/.stream() → (A) 자유 텍스트 모드
 *  - streamSupportWithMetadata(meta) : .entity() 동기 호출 → (B) 구조화 모드
 */
@Service
public class SupportService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SupportService(
            ChatClient.Builder builder,
            PerformanceLoggingAdvisor performanceAdvisor,
            ObjectMapper objectMapper,
            OrderTools orderTools
    ) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .defaultTools(orderTools)
                .build();
        this.objectMapper = objectMapper;
    }

    /** (B) 구조화 모드 — JSON 12필드. `/api/v1/support`. */
    public SupportResponse generateSupportResponse(String message) {
        return chatClient
                .prompt()
                .user(message)
                .call()
                .entity(SupportResponse.class);
    }

    /** (A) 자유 텍스트 모드 — 동기. `/api/v1/chat`, `/api/v1/assistant`. */
    public String chat(String message) {
        return chatClient
                .prompt()
                .user(message)
                .call()
                .content();
    }

    /** (A) 자유 텍스트 모드 — 스트리밍. `/api/v1/chat/stream` 의 token 부분. */
    public Flux<String> streamSupportResponse(String message) {
        return chatClient
                .prompt()
                .user(message)
                .stream()
                .content();
    }

    /**
     * SSE 옵션 A — 자유 텍스트 streaming + 마지막에 구조화 메타데이터 한 번.
     * - event: token  : (A) 자유 텍스트 토큰
     * - event: meta   : (B) 구조화 JSON 12필드 (별도 동기 호출)
     * Trade-off: LLM 호출 2회 (streaming + structured) → 비용 ×2.
     */
    public Flux<ServerSentEvent<String>> streamSupportWithMetadata(String message) {
        Flux<ServerSentEvent<String>> tokens = streamSupportResponse(message)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(chunk)
                        .build());

        Mono<ServerSentEvent<String>> meta = Mono.fromCallable(() -> {
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
