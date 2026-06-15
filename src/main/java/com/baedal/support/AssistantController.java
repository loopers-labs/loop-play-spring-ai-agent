package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling + Chat Memory + RAG가 적용된 자연어 응답 엔드포인트.
 * <p>
 * 4주차 변경점:
 * <ul>
 *     <li>{@link QuestionAnswerAdvisor}를 Advisor 체인에 추가 — 정책/FAQ 자동 검색 및 프롬프트 주입</li>
 * </ul>
 * <p>
 * Advisor 체인 순서 (order 기준, 낮은 값 먼저 실행):
 * <pre>
 *     MessageChatMemoryAdvisor   order=10   (3주차) 이전 대화 이력 주입
 *     QuestionAnswerAdvisor      order=20   (4주차) RAG 검색 결과 주입
 *     PerformanceLoggingAdvisor  order=100  (1주차) 전체 호출 시간 로깅
 * </pre>
 * Memory가 먼저 "아까 그 주문"을 해석해 주어야 Q&A가 "그 주문의 환불 정책"을 검색할 수 있다.
 * <p>
 * ⚠️ <b>주의</b>: {@link ChatClient.Builder}는 싱글톤 빈이므로 매 요청마다
 * {@code .defaultTools(...)} / {@code .defaultAdvisors(...)}를 호출하면 누적되어
 * 두 번째 요청부터 {@code "Multiple tools with the same name"} 오류가 발생한다.
 * 그래서 3주차부터 생성자에서 한 번만 {@link ChatClient}를 빌드해 재사용한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    // TODO [1단계-G] Advisor 체인에 ragAdvisor를 추가하라.
    //
    // 요구사항: 아래 생성자의 .defaultAdvisors(...)를 다음과 같이 바꾼다.
    //   .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
    //                    ^^^^^^^^^^^^^  ^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^
    //                    order=10       order=20    order=100
    //
    // 순서 주의: memoryAdvisor가 먼저, ragAdvisor가 두 번째, performanceAdvisor가 마지막.
    // Memory가 "아까 그 주문"의 orderId를 복원한 후 RAG가 "그 주문의 환불 정책"을 검색해야 한다.
    //
    // (이미 ragAdvisor는 생성자 파라미터로 주입받고 있다 — 체인에 끼우기만 하면 된다.)
    //
    // 설계 결정 질문 (README):
    //   - memoryAdvisor와 ragAdvisor의 순서를 뒤바꾸면 어떤 품질 저하가 생기는가?
    //     (힌트: "아까 그 주문 환불 돼요?" 질문에서 RAG가 먼저 실행되면 "그 주문"이 뭐인지
    //            알 수 없어 아무 정책이나 검색하게 된다)
    //   - 실제로 반대 순서가 더 나은 상황은 존재하는가? (5주차 Guardrail과 연결해 생각해 보라)
    public AssistantController(ChatClient.Builder builder,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               MessageChatMemoryAdvisor memoryAdvisor,
                               QuestionAnswerAdvisor ragAdvisor,
                               OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

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
