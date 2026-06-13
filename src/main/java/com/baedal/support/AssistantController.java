package com.baedal.support;

import com.baedal.support.guardrail.ForOutputGuardrailTestAdvisor;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import lombok.RequiredArgsConstructor;
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
 */

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

//    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final QuestionAnswerAdvisor ragAdvisor;
    private final InputGuardrailAdvisor inputGuardrail;
    private final OutputGuardrailAdvisor outputGuardrail;
    private final HandoffDetector handoffDetector;
    private final OrderTools orderTools;

    @PostMapping
    public String ask(@RequestBody ChatRequest req,
                      @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        log.info("[Assistant] sessionId={}, message={}", sessionId, req.message());

        // TODO [3단계-B] Handoff 선검사 — LLM 호출 전에 바로 상담원 연결 응답을 돌려주는 편이
        //    토큰 비용/지연/감정 대응 모두 유리하다.
        //    handoffDetector.detect(req.message()) 결과가 handoff==true 면 즉시 decision.message()를 리턴하라.
        //    왜 LLM 호출 전에 하는지를 README 설계 결정 섹션에 서술하라.

        // 3단계 구현
        HandoffDetector.HandoffDecision decision = handoffDetector.detect(req.message());
        if (decision.handoff()) {
            return decision.message();
        }

        // TODO [4단계-A] try/catch로 감싸서 LLM/Tool/VectorStore 예외 시 fallback(e)로 안전 응답을 돌려주라.
        //    스택트레이스는 절대 외부에 노출하지 않는다(log.error로 내부 로그에만 남김).
        return chatClient
                .prompt()
                .user(req.message())
                // 이 호출에 한해 Memory가 사용할 conversationId를 지정한다.
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .advisors(new ForOutputGuardrailTestAdvisor(req.message()))
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
