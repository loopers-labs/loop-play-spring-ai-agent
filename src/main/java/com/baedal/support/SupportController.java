package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

/**
 * Structured Output + Tool Calling + Chat Memory + RAG 통합 엔드포인트.
 * <p>
 * 4주차 변경점: {@link QuestionAnswerAdvisor}(order=20)를 체인에 추가한다.
 * Triage 응답도 정책/FAQ 근거가 있으면 더 정확한 카테고리/다음 액션을 반환한다.
 * <p>
 * ⚠️ {@link ChatClient.Builder}는 싱글톤 빈이므로 핸들러 내부에서
 * {@code .defaultXxx()}를 매 요청마다 호출하면 누적된다. 생성자에서 한 번만 빌드해 재사용한다.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;

    // TODO [1단계-I] SupportController에도 동일한 Advisor 체인(memory → rag → performance)을 적용하라.
    //
    // 요구사항: 아래 생성자의 .defaultAdvisors(...)를 다음과 같이 바꾼다.
    //   .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
    //
    // AssistantController와 완전히 동일한 순서여야 한다 — 두 엔드포인트가
    // 같은 정책 지식과 같은 대화 맥락을 공유해야 일관된 상담이 된다.
    public SupportController(ChatClient.Builder builder,
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
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        return chatClient.prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
