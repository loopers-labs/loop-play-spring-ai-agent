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
 * <p>
 * 5주차 변경점:
 * <ul>
 *     <li>{@link InputGuardrailAdvisor}(order=5) — Prompt Injection / 역할 이탈 / 길이 제한 입력 차단</li>
 *     <li>{@link OutputGuardrailAdvisor}(order=50) — 민감 정보 마스킹 / 시스템 프롬프트 유출 차단</li>
 *     <li>{@link HandoffDetector} — 감정 고조 / 명시적 요청 / 법적 이슈 감지 시 상담원 연결 응답</li>
 *     <li>Tool / LLM 호출 실패 시 Graceful Fallback 응답 ({@link #fallback(Throwable)})</li>
 * </ul>
 * <p>
 * Advisor 체인 순서 (order 기준, 낮은 값 먼저 실행):
 * <pre>
 *     InputGuardrailAdvisor      order=5    (5주차) 입력 검증 / 차단
 *     MessageChatMemoryAdvisor   order=10   (3주차) 이전 대화 이력 주입
 *     QuestionAnswerAdvisor      order=20   (4주차) RAG 검색 결과 주입
 *     OutputGuardrailAdvisor     order=50   (5주차) 응답 마스킹 / 유출 차단
 *     PerformanceLoggingAdvisor  order=100  (1주차) 전체 호출 시간 로깅
 * </pre>
 * <p>
 * ⚠️ {@link ChatClient.Builder}는 싱글톤이므로 요청마다 {@code .defaultTools()/.defaultAdvisors()}를
 * 호출하면 누적되어 {@code "Multiple tools with the same name"} 오류가 난다. 그래서 생성자에서 한 번만
 * {@link ChatClient}를 조립해 재사용한다(3·4주차와 동일).
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
        // inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20) → outputGuardrail(50) → performanceAdvisor(100)
        // inputGuardrail은 Memory/RAG/LLM보다 앞에서 공격을 short-circuit 차단(비용 0),
        // outputGuardrail은 Performance보다 안쪽이라 "마스킹된" 최종 응답이 로깅된다.
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

        // 빈/blank 입력은 ChatClient.user("")가 advisor 도달 "전"에 거부하므로,
        // InputGuardrailAdvisor가 못 잡는다. LLM 호출 전에 컨트롤러에서 선차단한다(비용 0).
        if (req.message() == null || req.message().isBlank()) {
            GuardrailResult result = inputGuardrail.check(req.message());
            log.warn("[InputGuardrail] 차단 — reason={} | input.len=0", result.reason());
            return result.fallbackMessage();
        }

        // Handoff 선검사 — LLM 호출 "전"에 상담원 전환 응답을 즉시 반환한다(비용 0, 수십 ms).
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Handoff] 상담원 전환 — reason={}", handoff.reason());
            return handoff.message();
        }

        return chatClient.prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    /**
     * LLM / Tool / VectorStore 호출 실패 시 고객에게 보낼 안전한 Fallback 응답.
     * 스택 트레이스는 절대 노출하지 않는다. 내부 로그에만 남긴다.
     *
     * TODO [4단계-B] 아래 메서드를 활용하여 예외 시 안내 메시지를 돌려주는 흐름을 완성하라.
     *   메시지 톤은 고객 친화적으로, 장애 상황에서도 상담원 연결 경로("1600-0987")를 안내할 것.
     */
    @SuppressWarnings("unused")
    private String fallback(Throwable e) {
        log.error("[Assistant] 응답 생성 실패 — {}", e.toString(), e);
        return "죄송해요, 지금 일시적인 문제가 발생했어요. 잠시 후 다시 시도하시거나, "
                + "급하시면 '상담원'이라고 입력해 주세요. (연결 번호: 1600-0987)";
    }
}
