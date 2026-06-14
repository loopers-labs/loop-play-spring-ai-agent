package com.baedal.support;

import com.baedal.support.guardrail.GuardrailResult;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
                             InputGuardrailAdvisor inputGuardrail,
                             OutputGuardrailAdvisor outputGuardrail,
                             HandoffDetector handoffDetector,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             PerformanceLoggingAdvisor performanceLoggingAdvisor,
                             OrderTools orderTools
    ) {
        this.inputGuardrail = inputGuardrail;
        this.outputGuardrail = outputGuardrail;
        this.handoffDetector = handoffDetector;

        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // [1단계-C] inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20)
                //          → outputGuardrail(50) → performanceAdvisor(100) 순.
                // inputGuardrail이 최외곽: Memory/RAG/LLM 전에 공격을 short-circuit으로 차단해 비용을 0으로 만든다.
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceLoggingAdvisor)
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
        // 빈/공백 입력은 ChatClient.user()의 hasText 검사에서 Advisor 체인보다 먼저 거부되어
        // HTTP 500이 난다. .user() 호출 전에 선검사해 구조화 응답으로 친화적으로 차단한다.
        if (req.message() == null || req.message().isBlank()) {
            GuardrailResult guard = inputGuardrail.check(req.message());

            return new SupportResponse(
                    guard.fallbackMessage(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.LOW,
                    "재입력 안내",
                    List.of(),
                    false
            );
        }

        return chatClient
                .prompt()
                .user(req.message())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
