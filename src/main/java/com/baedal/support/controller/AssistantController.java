package com.baedal.support.controller;

import com.baedal.support.dto.ChatRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling + Chat Memory가 적용된 자연어 응답 엔드포인트.
 *
 * <p>3주차 변경점 (숙제에서 직접 구현):
 * <ul>
 *     <li>{@link MessageChatMemoryAdvisor}를 Advisor 체인에 추가 — 이전 대화 이력을 자동 주입
 *         (Advisor 등록은 {@code AssistantChatClientConfig}에서 수행)</li>
 *     <li>{@code X-Session-Id} HTTP 헤더로 고객 세션을 식별하고
 *         {@link ChatMemory#CONVERSATION_ID} 파라미터에 전달</li>
 *     <li>헤더가 없으면 {@code "default"} 세션으로 폴백 (개발용)</li>
 * </ul>
 *
 * <p>구현 후 관찰 포인트: 같은 세션 ID로 연속 호출 시 "그거", "방금 주문한 거" 같은
 * 지시 대명사가 Tool 호출 파라미터(orderId)로 정확히 치환되는 과정을 DEBUG 로그에서 확인할 수 있다.
 *
 * <p>구조 메모: {@link ChatClient.Builder}가 아니라 조립이 끝난 {@link ChatClient}를 주입받는다.
 * Builder는 싱글톤이라 요청마다 defaultTools/defaultAdvisors를 호출하면 누적되어
 * "Multiple tools with the same name" 오류가 발생하기 때문이다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient assistantChatClient;

    /**
     * X-Session-Id 헤더로 고객 세션을 식별하고 ChatMemory 의 conversationId 로 전달.
     * <p>
     * defaultValue="default" 는 개발 편의용 — 헤더 없는 여러 클라이언트가 같은 세션을 공유하므로
     * 프로덕션에선 400 응답으로 바꿔야 한다. 시나리오 5(보안 사고 시뮬레이션) 에서 직접 재현.
     * <p>
     * .advisors(...) 를 호출 체인에 두는 이유는 ChatClient 빈을 모든 세션이 공유하기 때문 —
     * 세션별 conversationId 는 요청 단위로만 의미가 있다.
     */
    @PostMapping
    public String ask(@Valid @RequestBody ChatRequest req,
                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        log.info("[Assistant] sessionId={} message={}", sessionId, req.message());
        return assistantChatClient
                .prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
