package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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

//    private final ChatClient.Builder builder;
//    private final PerformanceLoggingAdvisor performanceAdvisor;
//    private final MessageChatMemoryAdvisor memoryAdvisor; // 3주차에서 추가
//    private final OrderTools orderTools;

    private final ChatClient chatClient;

    public SupportController(ChatClient.Builder builder,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // TODO: ragAdvisor를 memoryAdvisor 다음, performanceAdvisor 앞에 추가하라.
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
