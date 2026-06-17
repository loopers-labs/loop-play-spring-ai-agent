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
 * ⚠️ <b>주의 1</b>: {@link ChatClient.Builder}는 싱글톤 빈이므로 매 요청마다
 * {@code .defaultTools(...)} / {@code .defaultAdvisors(...)}를 호출하면 누적되어
 * 두 번째 요청부터 {@code "Multiple tools with the same name"} 오류가 발생한다.
 * 그래서 3주차부터 생성자에서 한 번만 {@link ChatClient}를 빌드해 재사용한다.
 * <p>
 * ⚠️ <b>주의 2</b>: 빈/공백 입력은 {@code .user("")}가 {@code "text cannot be null or empty"}로
 * 거부한다. 이 검증은 {@code .call()} 진입 전(=advisor 체인 실행 전)에 일어나므로
 * {@link InputGuardrailAdvisor}가 막을 수 없다. 따라서 빈/공백 입력만 컨트롤러에서 선검사한다.
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
        // [1단계-B] 실행 순서는 각 Advisor의 getOrder()가 정하지만, 가독성을 위해 order 오름차순으로 나열한다:
        //   inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20) → outputGuardrail(50) → performanceAdvisor(100)
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

        // 빈/공백 입력은 .user()가 거부하므로(.call() 진입 전) InputGuardrailAdvisor가 못 막는다.
        // → 컨트롤러에서 선검사로 EMPTY_INPUT만 차단. 그 외(injection/길이)는 advisor(order=5)가 담당한다.
        if (req.message() == null || req.message().isBlank()) {
            GuardrailResult guard = inputGuardrail.check(req.message());
            log.warn("[InputGuardrail] 선검사 차단 — reason={}", guard.reason());
            return guard.fallbackMessage();
        }

        // [3단계-B] Handoff 선검사 — LLM 호출 전에 상담원 전환 응답을 돌려준다.
        //   토큰/지연 절감 + 일관된 문구 + LLM의 "도와드릴게요" 회피 방지.
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(req.message());
        if (handoff.handoff()) {
            log.info("[Assistant] 상담원 전환 — reason={}", handoff.reason());
            return handoff.message();
        }

        // [4단계-A] LLM/Tool/VectorStore 호출을 try/catch로 감싸 예외 시 안전 Fallback.
        //   스택트레이스는 외부에 노출하지 않고 fallback() 내부 log.error로만 남긴다.
        try {
            return chatClient.prompt()
                    .user(req.message())
                    // 이 호출에 한해 Memory가 사용할 conversationId를 지정한다.
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
        } catch (Exception e) {
            return fallback(e);
        }
    }

    /**
     * LLM / Tool / VectorStore 호출 실패 시 고객에게 보낼 안전한 Fallback 응답.
     * 스택 트레이스는 절대 노출하지 않는다. 내부 로그에만 남긴다.
     *
     * [4단계-B] ask()의 try/catch에서 호출된다. 예외 원인은 내부 로그에만, 응답엔 안전 문구만.
     */
    private String fallback(Throwable e) {
        log.error("[Assistant] 응답 생성 실패 — {}", e.toString(), e);
        return "죄송해요, 지금 일시적인 문제가 발생했어요. 잠시 후 다시 시도하시거나, "
                + "급하시면 '상담원'이라고 입력해 주세요. (연결 번호: 1600-0987)";
    }
}
