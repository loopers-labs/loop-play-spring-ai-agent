package com.baedal.support;

import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

/**
 * 1주차에서 만든 Structured Output 엔드포인트.
 * 2주차에는 여기에도 OrderTools를 등록하여 Tool Calling과 Structured Output이
 * 함께 동작할 수 있는지 직접 확인한다.
 */

/**
 * Structured Output + Tool Calling + Chat Memory + RAG + Guardrail 통합 엔드포인트.
 * <p>
 * 5주차 변경점: Input/Output Guardrail Advisor를 체인에 추가.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;
    private final OrderTools orderTools;
    private final InputGuardrailAdvisor inputGuardrail;
    private final OutputGuardrailAdvisor outputGuardrail;
    private final HandoffDetector handoffDetector;

    public SupportController(ChatClient.Builder builder,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             PerformanceLoggingAdvisor performanceLoggingAdvisor,
                             OrderTools orderTools
    ) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // [1단계-H] memoryAdvisor(10) → ragAdvisor(20) → performanceAdvisor(100) 순.
                // memoryAdvisor가 첫 번째: 프롬프트 조립 전에 이전 대화 이력을 주입한다.
                .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceLoggingAdvisor)
                .defaultTools(orderTools)
                .build();
        this.orderTools = orderTools;
    }

    // TODO [3단계-C] Handoff 선검사를 추가하라.
    //   handoffDetector.detect(req.message())의 handoff==true면 Structured Output 스키마에 맞춰
    //   SupportResponse를 수동 조립하여 반환한다.
    //     - answer: decision.message()
    //     - category: Category.ETC
    //     - urgency:  Urgency.HIGH
    //     - action:   "상담원 연결 진행"
    //     - nextSteps: List.of() 또는 ["상담원 응대 대기"]

    // TODO [1단계-C] defaultAdvisors에 inputGuardrail / outputGuardrail을 추가하라.
    //   권장 순서: inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20)
    //            → outputGuardrail(50) → performanceAdvisor(100)
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
