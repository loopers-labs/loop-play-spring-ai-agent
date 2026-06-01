package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

/**
 * 1주차에서 만든 Structured Output 엔드포인트.
 * 2주차에는 여기에도 OrderTools를 등록하여 Tool Calling과 Structured Output이
 * 함께 동작할 수 있는지 직접 확인한다.
 */

/**
 * Structured Output + Tool Calling + Chat Memory 통합 엔드포인트.
 *
 * <p>3주차 변경점 (숙제에서 직접 구현): Memory Advisor 추가 + X-Session-Id 헤더 처리.
 * Triage 용도에도 Memory를 연결해 두면 "같은 세션에서 반복 분류할 때 맥락이 유지"된다.
 *
 * <p>구현 방법은 {@link AssistantController}와 동일하다. 거기서 배운 패턴을 여기에도 적용하라.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;
    private final OrderTools orderTools;

    public SupportController(ChatClient.Builder builder,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             PerformanceLoggingAdvisor performanceLoggingAdvisor,
                             OrderTools orderTools
    ) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // memoryAdvisor가 첫 번째: 프롬프트 조립 전에 이전 대화 이력을 주입한다.
                .defaultAdvisors(memoryAdvisor, performanceLoggingAdvisor)
                .defaultTools(orderTools)
                .build();
        this.orderTools = orderTools;
    }

    // 같은 세션 ID로 AssistantController와 SupportController 양쪽을 호출하면
    // 두 엔드포인트가 같은 대화 이력을 공유한다는 사실을 README에 검증 기록으로 남겨라.
    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        return chatClient
                .prompt()
                .user(req.message())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
