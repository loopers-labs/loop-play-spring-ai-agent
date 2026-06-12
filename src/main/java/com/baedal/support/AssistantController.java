package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling + Chat Memory가 적용된 자연어 응답 엔드포인트.
 *
 * <p>3주차 변경점 (숙제에서 직접 구현):
 * <ul>
 *     <li>{@link MessageChatMemoryAdvisor}를 Advisor 체인에 추가 — 이전 대화 이력을 자동 주입
 *         (Advisor 등록은 {@link AssistantChatClientConfig}에서 수행)</li>
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
 *
 *
 * * Tool Calling + Chat Memory + RAG가 적용된 자연어 응답 엔드포인트.
 *  * <p>
 *  * 4주차 변경점:
 *  * <ul>
 *  *     <li>{@link QuestionAnswerAdvisor}를 Advisor 체인에 추가 — 정책/FAQ 자동 검색 및 프롬프트 주입</li>
 *  * </ul>
 *  * <p>
 *  * Advisor 체인 순서 (order 기준, 낮은 값 먼저 실행):
 *  * <pre>
 *  *     MessageChatMemoryAdvisor   order=10   (3주차) 이전 대화 이력 주입
 *  *     QuestionAnswerAdvisor      order=20   (4주차) RAG 검색 결과 주입
 *  *     PerformanceLoggingAdvisor  order=100  (1주차) 전체 호출 시간 로깅
 *  * </pre>
 *  * Memory가 먼저 "아까 그 주문"을 해석해 주어야 Q&A가 "그 주문의 환불 정책"을 검색할 수 있다.
 *  * <p>
 *  * ⚠️ <b>주의</b>: {@link ChatClient.Builder}는 싱글톤 빈이므로 매 요청마다
 *  * {@code .defaultTools(...)} / {@code .defaultAdvisors(...)}를 호출하면 누적되어
 *  * 두 번째 요청부터 {@code "Multiple tools with the same name"} 오류가 발생한다.
 *  * 그래서 3주차부터 생성자에서 한 번만 {@link ChatClient}를 빌드해 재사용한다.
 */

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    @PostMapping
    public String ask(@RequestBody ChatRequest req,
                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        log.info("[Assistant] sessionId={}, message={}", sessionId, req.message());

        return chatClient.prompt()
                .user(req.message())
                // 이 호출에 한해 Memory가 사용할 conversationId를 지정한다.
                // ChatMemory.CONVERSATION_ID = "chat_memory_conversation_id"
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
