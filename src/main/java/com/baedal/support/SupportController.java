package com.baedal.support;

import com.baedal.support.guardrail.GuardrailResult;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Structured Output + Tool Calling + Chat Memory + RAG + Guardrail 통합 엔드포인트.
 *
 * Advisor 체인 순서: InputGuardrail(5) → Memory(10) → RAG(20) → OutputGuardrail(50) → Performance(100)
 */
@Slf4j
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
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        log.info("[Support] sessionId={}, message={}", sessionId, req.message());

        // Spring AI가 .user("") 시점에 예외를 던지므로 체인 진입 전에 먼저 차단
        GuardrailResult guardrailResult = inputGuardrail.check(req.message());
        if (!guardrailResult.allowed()) {
            log.warn("[Support/InputGuardrail] 차단 — reason={}", guardrailResult.reason());
            return blocked(guardrailResult);
        }

        // LLM 호출 전 상담원 전환 선검사
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Support] Handoff 감지 — reason={}", handoff.reason());
            return new SupportResponse(
                    handoff.message(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.HIGH,
                    "상담원 연결 진행",
                    List.of(),
                    SupportResponse.CustomerSentiment.FRUSTRATED,
                    0,
                    true
            );
        }

        try {
            return chatClient.prompt()
                    .user(req.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .entity(SupportResponse.class);
        } catch (Exception e) {
            return fallback(e);
        }
    }

    private SupportResponse blocked(GuardrailResult result) {
        return new SupportResponse(
                result.fallbackMessage(),
                SupportResponse.Category.ETC,
                SupportResponse.Urgency.LOW,
                "입력 재요청",
                List.of(),
                SupportResponse.CustomerSentiment.NEUTRAL,
                0,
                false
        );
    }

    private SupportResponse fallback(Exception e) {
        log.error("[Support] 처리 중 오류 발생 — {}", e.getMessage(), e);
        return new SupportResponse(
                "죄송해요, 일시적인 오류가 발생했어요. 상담원(1600-0987)에게 문의해 주세요.",
                SupportResponse.Category.ETC,
                SupportResponse.Urgency.HIGH,
                "상담원 연결 권장",
                List.of(),
                SupportResponse.CustomerSentiment.NEUTRAL,
                0,
                true
        );
    }
}
