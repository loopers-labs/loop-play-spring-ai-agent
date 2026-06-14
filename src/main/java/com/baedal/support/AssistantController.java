package com.baedal.support;

import com.baedal.support.guardrail.GuardrailResult;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;
    private final InputGuardrailAdvisor inputGuardrail;
    private final OutputGuardrailAdvisor outputGuardrail;
    private final HandoffDetector handoffDetector;

    // TODO [1단계-G] Advisor 체인에 ragAdvisor를 추가하라.
    //
    // 요구사항: 아래 생성자의 .defaultAdvisors(...)를 다음과 같이 바꾼다.
    //   .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
    //                    ^^^^^^^^^^^^^  ^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^
    //                    order=10       order=20    order=100
    //
    // 순서 주의: memoryAdvisor가 먼저, ragAdvisor가 두 번째, performanceAdvisor가 마지막.
    // Memory가 "아까 그 주문"의 orderId를 복원한 후 RAG가 "그 주문의 환불 정책"을 검색해야 한다.
    //
    // (이미 ragAdvisor는 생성자 파라미터로 주입받고 있다 — 체인에 끼우기만 하면 된다.)
    //
    // 설계 결정 질문 (README):
    //   - memoryAdvisor와 ragAdvisor의 순서를 뒤바꾸면 어떤 품질 저하가 생기는가?
    //     (힌트: "아까 그 주문 환불 돼요?" 질문에서 RAG가 먼저 실행되면 "그 주문"이 뭐인지
    //            알 수 없어 아무 정책이나 검색하게 된다)
    //   - 실제로 반대 순서가 더 나은 상황은 존재하는가? (5주차 Guardrail과 연결해 생각해 보라)
    public AssistantController(ChatClient.Builder builder,
                               InputGuardrailAdvisor inputGuardrail,
                               OutputGuardrailAdvisor outputGuardrail,
                               HandoffDetector handoffDetector,
                               MessageChatMemoryAdvisor memoryAdvisor,
                               QuestionAnswerAdvisor ragAdvisor,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        this.inputGuardrail = inputGuardrail;
        this.outputGuardrail = outputGuardrail;
        this.handoffDetector = handoffDetector;

        this.chatClient = builder
                .defaultSystem(BaedalPrompt.ASSISTANT_SYSTEM_PROMPT)
                // [1단계-B] inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20)
                //          → outputGuardrail(50) → performanceAdvisor(100) 순.
                // inputGuardrail이 최외곽: Memory/RAG/LLM 전에 공격을 short-circuit으로 차단해 비용을 0으로 만든다.
                // outputGuardrail은 Performance 안쪽: 마스킹된 응답이 성능 로그에 찍히도록 한다.
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor, new SimpleLoggerAdvisor())
                .defaultTools(orderTools)
                .build();
    }

    // TODO [1단계-B] Advisor 체인에 inputGuardrail / outputGuardrail을 추가하라.
    //   권장 순서: inputGuardrail(5) → memoryAdvisor(10) → ragAdvisor(20)
    //            → outputGuardrail(50) → performanceAdvisor(100)
    //   왜 inputGuardrail이 Memory보다 앞이고, outputGuardrail이 Performance보다 안쪽인지를
    //   README 설계 결정 섹션에 서술하라.

    // TODO [3단계-B] Handoff 선검사 — LLM 호출 전에 바로 상담원 연결 응답을 돌려주는 편이
    //    토큰 비용/지연/감정 대응 모두 유리하다.
    //    handoffDetector.detect(req.message()) 결과가 handoff==true 면 즉시 decision.message()를 리턴하라.
    //    왜 LLM 호출 전에 하는지를 README 설계 결정 섹션에 서술하라.

    // TODO [4단계-A] try/catch로 감싸서 LLM/Tool/VectorStore 예외 시 fallback(e)로 안전 응답을 돌려주라.
    //    스택트레이스는 절대 외부에 노출하지 않는다(log.error로 내부 로그에만 남김).
    @PostMapping
    public String ask(@RequestBody ChatRequest req,
                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        log.info("[Assistant] sessionId={}, message={}", sessionId, req.message());

        // 빈/공백 입력은 ChatClient.user()의 hasText 검사에서 Advisor 체인보다 먼저 거부되어
        // HTTP 500이 난다. inputGuardrail.check()의 EMPTY_INPUT 분기에 도달하지 못하므로,
        // .user() 호출 전에 직접 선검사해 친화적으로 차단한다. (인젝션/길이초과는 advisor가 담당)
        if (req.message() == null || req.message().isBlank()) {
            GuardrailResult guard = inputGuardrail.check(req.message());
            log.warn("[Assistant] 입력 차단 — reason={}", guard.reason());

            return guard.fallbackMessage();
        }

        // [3단계-B] LLM 호출 전에 상담원 전환 신호를 선검사한다.
        // 전환 대상이면 chatClient.call()에 도달하지 않아 Ollama 추론(토큰/지연)이 0이 된다.
        HandoffDetector.HandoffDecision decision = handoffDetector.detect(req.message());
        if (decision.handoff()) {
            log.info("[Handoff] reason={} — LLM 호출 없이 상담원 연결 응답", decision.reason());

            return decision.message();
        }

        return chatClient
                .prompt()
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
