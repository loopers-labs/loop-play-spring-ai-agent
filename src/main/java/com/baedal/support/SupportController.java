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
 * Structured Output + Tool Calling + Chat Memory + RAG + Guardrail 통합 엔드포인트.
 * <p>
 * 5주차 변경점: Input/Output Guardrail Advisor를 체인에 추가.
 * <p>
 * ⚠️ {@link ChatClient.Builder}는 싱글톤 빈이므로 핸들러 내부에서 매 요청마다
 * {@code .defaultXxx()}를 호출하면 누적된다(두 번째 요청부터 "Multiple tools with the same name").
 * 생성자에서 한 번만 빌드해 재사용한다.
 * <p>
 * ⚠️ 빈/공백 입력은 {@code .user("")}가 거부하므로(advisor 도달 전) 컨트롤러에서 선검사해
 * {@link SupportResponse}로 안내를 조립한다.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;
    private final InputGuardrailAdvisor inputGuardrail;
    private final HandoffDetector handoffDetector;

    public SupportController(ChatClient.Builder builder,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             InputGuardrailAdvisor inputGuardrail,
                             OutputGuardrailAdvisor outputGuardrail,
                             HandoffDetector handoffDetector,
                             OrderTools orderTools) {
        this.inputGuardrail = inputGuardrail;
        this.handoffDetector = handoffDetector;
        // [1단계-C] 실행 순서는 getOrder()가 정하지만 가독성을 위해 order 오름차순으로 나열.
        //   inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20) → outputGuardrail(50) → performanceAdvisor(100)
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        // 빈/공백 입력은 .user()가 거부하므로(advisor 도달 전) 컨트롤러에서 선검사해 스키마에 맞춰 안내한다.
        if (req.message() == null || req.message().isBlank()) {
            GuardrailResult guard = inputGuardrail.check(req.message());
            return new SupportResponse(
                    guard.fallbackMessage(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.LOW,
                    "문의 내용을 입력해 주세요",
                    List.of()
            );
        }

        // [3단계-C] Handoff 선검사 — LLM 호출 전에 Structured Output 스키마로 상담원 전환 응답을 조립.
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            return new SupportResponse(
                    handoff.message(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.HIGH,
                    "상담원 연결 진행",
                    List.of("상담원 응대 대기")
            );
        }

        return chatClient.prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
