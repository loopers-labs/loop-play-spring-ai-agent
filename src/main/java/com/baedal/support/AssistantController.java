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

/**
 * Tool Calling + Chat Memory + RAG + Guardrail이 적용된 자연어 응답 엔드포인트.
 *
 * Advisor 체인 순서 (order 기준, 낮은 값 먼저 실행):
 * <pre>
 *     InputGuardrailAdvisor      order=5    입력 차단 (short-circuit)
 *     MessageChatMemoryAdvisor   order=10   이전 대화 이력 주입
 *     QuestionAnswerAdvisor      order=20   RAG 검색 결과 주입
 *     OutputGuardrailAdvisor     order=50   출력 마스킹/유출 차단
 *     PerformanceLoggingAdvisor  order=100  전체 호출 시간 로깅
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;
    private final InputGuardrailAdvisor inputGuardrail;
    private final HandoffDetector handoffDetector;

    public AssistantController(ChatClient.Builder builder,
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
    public String ask(@RequestBody ChatRequest req,
                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        log.info("[Assistant] sessionId={}, message={}", sessionId, req.message());

        // Spring AI가 .user("") 시점에 예외를 던지므로 체인 진입 전에 먼저 차단
        GuardrailResult guardrailResult = inputGuardrail.check(req.message());
        if (!guardrailResult.allowed()) {
            log.warn("[Assistant/InputGuardrail] 차단 — reason={}", guardrailResult.reason());
            return guardrailResult.fallbackMessage();
        }

        // LLM 호출 전 상담원 전환 선검사
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Assistant] Handoff 감지 — reason={}", handoff.reason());
            return handoff.message();
        }

        try {
            return chatClient.prompt()
                    .user(req.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
        } catch (Exception e) {
            return fallback(e);
        }
    }

    private String fallback(Exception e) {
        log.error("[Assistant] 처리 중 오류 발생 — {}", e.getMessage(), e);
        return "죄송해요, 일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주시거나 상담원(1600-0987)에게 문의해 주세요.";
    }
}
