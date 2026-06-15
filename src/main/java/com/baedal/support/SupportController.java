package com.baedal.support;

import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final QuestionAnswerAdvisor ragAdvisor;
    private final InputGuardrailAdvisor inputGuardrail;
    private final OutputGuardrailAdvisor outputGuardrail;
    private final HandoffDetector handoffDetector;
    private final OrderTools orderTools;

    @PostMapping
    public SupportResponse triage(@Valid @RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        // Handoff 선검사 — LLM 호출 전에 상담원 전환 여부를 판별해 토큰/지연을 아낀다.
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Support] Handoff 전환 — reason={} (LLM 미호출)", handoff.reason());
            // Structured Output 스키마에 맞춰 수동 조립.
            return new SupportResponse(
                    handoff.message(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.HIGH,
                    "상담원 연결 진행",
                    List.of("상담원 응대 대기"),
                    null,
                    SupportResponse.Actionability.ESCALATED
            );
        }

        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // Advisor 체인 — 각 Advisor의 getOrder() 기준으로 정렬되어 실행된다.
                //   inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20)
                //   → outputGuardrail(50) → performanceAdvisor(100)
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build()
                .prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
