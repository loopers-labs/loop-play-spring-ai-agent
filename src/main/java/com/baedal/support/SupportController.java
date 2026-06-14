package com.baedal.support;

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
 * <p>
 * 5주차 변경점: Input/Output Guardrail Advisor를 체인에 추가.
 * <p>
 * ⚠️ {@link ChatClient.Builder}는 싱글톤이므로 생성자에서 한 번만 {@link ChatClient}를 조립해 재사용한다
 * (요청마다 {@code .defaultTools()} 호출 시 "Multiple tools with the same name" 오류).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;
    private final HandoffDetector handoffDetector;

    public SupportController(ChatClient.Builder builder,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             InputGuardrailAdvisor inputGuardrail,
                             OutputGuardrailAdvisor outputGuardrail,
                             HandoffDetector handoffDetector,
                             OrderTools orderTools) {
        this.handoffDetector = handoffDetector;
        // AssistantController와 동일: inputGuardrail(5) → memory(10) → rag(20) → outputGuardrail(50) → performance(100)
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        // Handoff 선검사 — LLM 호출 전에 Structured Output 스키마로 수동 조립해 반환한다.
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Handoff] 상담원 전환 — reason={}", handoff.reason());
            return new SupportResponse(
                    handoff.message(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.HIGH,
                    "상담원 연결 진행",
                    List.of("상담원 응대 대기"));
        }

        return chatClient.prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
