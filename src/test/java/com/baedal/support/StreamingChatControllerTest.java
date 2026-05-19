package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Ollama는 비결정적(매번 다른 토큰)이라 E2E 불가 → 웹 레이어만 슬라이스 로드하고 ChatClient를 mock으로 대체
@WebFluxTest(StreamingChatController.class)
class StreamingChatControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    // 스프링 빈으로 주입되어야 하므로 @MockitoBean — mock()으로 대체 불가
    @MockitoBean
    private ChatClient.Builder builder;

    @Test
    void chatStream_SSE_스트림을_반환한다() {
        // Ollama 대신 가짜 토큰 3개를 반환하도록 4단계 mock 체인 구성
        // ChatClientRequestSpec, StreamResponseSpec은 내부 인터페이스라 직접 인스턴스화 불가 → mock()
        var fakeTokens = Flux.just("안녕", "하세요", "고객님");
        var chatClient = mock(ChatClient.class);
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(builder.defaultSystem(BaedalPrompt.SYSTEM_PROMPT)).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(fakeTokens);

        // POST /api/v1/chat/stream 호출 후 SSE 응답 스트림 수신
        var responseBody = webTestClient.post()
                .uri("/api/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatRequest("배달 어디쯤이에요?"))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();

        // 토큰이 순서대로 도착하고 스트림이 정상 종료되는지 검증
        // SYSTEM_PROMPT 누락 시 실패 — 구현에서 빠뜨리는 것을 방지
        responseBody
                .as(StepVerifier::create)
                .expectNext("안녕", "하세요", "고객님")
                .verifyComplete();

        verify(builder).defaultSystem(BaedalPrompt.SYSTEM_PROMPT);
    }

    @Test
    void chatStream_LLM_오류시_에러_토큰을_스트리밍한다() {
        // LLM 호출 시 예외가 발생하도록 mock 체인 구성
        var errorFlux = Flux.<String>error(new RuntimeException("Ollama 연결 실패"));
        var chatClient = mock(ChatClient.class);
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(builder.defaultSystem(BaedalPrompt.SYSTEM_PROMPT)).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(errorFlux);

        // 클라이언트는 이미 200 OK를 받은 상태 — HTTP 에러 코드 대신 에러 토큰으로 전달
        var responseBody = webTestClient.post()
                .uri("/api/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatRequest("배달 어디쯤이에요?"))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();

        // 스트림이 종료되지 않고 에러 토큰 1개를 반환하는지 검증
        responseBody
                .as(StepVerifier::create)
                .expectNext("[오류] 상담 응답 생성 중 문제가 발생했습니다.")
                .verifyComplete();
    }
}
