package com.baedal.support;

import com.baedal.assistant.tool.OrderTools;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.HandoffDetector.HandoffResult;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;
    private final HandoffDetector handoffDetector;

    public SupportController(ChatClient.Builder builder,
                             InputGuardrailAdvisor inputGuardrail,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             OutputGuardrailAdvisor outputGuardrail,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             HandoffDetector handoffDetector,
                             OrderTools orderTools) {
        this.handoffDetector = handoffDetector;
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                // Round 5: inputGuardrail(5) → memory(10) → rag(20) → outputGuardrail(50) → performance(100).
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@Valid @RequestBody ChatRequest req,
                                  @RequestHeader("X-Session-Id") String sessionId) {
        try {
            // LLM 호출 전에 상담원 전환 선검사. 전환이면 모델 대신 SupportResponse를 수동 조립해 돌려준다.
            HandoffResult handoff = handoffDetector.detect(req.message());
            if (handoff.handoff()) {
                log.info("[Handoff] trigger={} — LLM 호출 없음 (support)", handoff.trigger());
                return new SupportResponse(
                        handoff.message(),
                        SupportResponse.Category.ETC,
                        SupportResponse.Urgency.HIGH,
                        "상담원 연결 진행",
                        List.of(),
                        null,
                        SupportResponse.Confidence.HIGH);
            }
            return chatClient.prompt()
                    .user(req.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .entity(SupportResponse.class);
        } catch (Exception e) {
            return fallback(e);
        }
    }

    /**
     * Tool/LLM 실패나 구조화 응답 파싱 실패 시 안전 응답. 스택 트레이스는 내부 로그(log.error)에만 남기고,
     * 스키마를 지키는 SupportResponse(ETC/HIGH/상담원 연결)로 돌려준다.
     */
    private SupportResponse fallback(Exception e) {
        log.error("[Fallback] support 처리 실패 — 내부 오류 (응답에는 미노출)", e);
        return new SupportResponse(
                "일시적인 오류로 자동 분류에 실패했습니다. 상담원 연결로 안내드리겠습니다. 고객센터 1600-0987.",
                SupportResponse.Category.ETC,
                SupportResponse.Urgency.HIGH,
                "상담원 연결 진행",
                List.of(),
                null,
                SupportResponse.Confidence.LOW);
    }
}
