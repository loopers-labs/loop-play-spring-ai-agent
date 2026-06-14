package com.baedal.support.service;

import com.baedal.support.advisor.PerformanceLoggingAdvisor;
import com.baedal.support.dto.SupportResponse;
import com.baedal.support.guardrail.GuardrailResult;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.prompt.BaedalPrompt;
import com.baedal.support.tool.OrderTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 단일 ChatClient 로 동기·스트리밍·구조화 응답을 모두 처리한다.
 * <p>
 * Round 1 에서는 SYSTEM_PROMPT / STREAMING_PROMPT 두 ChatClient 로 분리했었지만,
 * Round 2 에서 다음 두 이유로 단일 prompt 로 통합:
 *  - SSE 옵션 A (token + meta) 가 분리의 원래 동기를 약화시킴
 *  - STREAMING_PROMPT 의 "확인 후 안내" 회피 표현이 Tool 호출 회피의 부작용으로 작용
 * <p>
 * 호출 메서드별 모드 분기:
 *  - generateSupportResponse  : .entity()  → Spring AI 가 JSON schema 첨부 → (B) 구조화 모드
 *  - chat / streamSupportResponse / streamSupportWithMetadata(token) : .content()/.stream() → (A) 자유 텍스트 모드
 *  - streamSupportWithMetadata(meta) : .entity() 동기 호출 → (B) 구조화 모드
 */
@Service
public class SupportService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final InputGuardrailAdvisor inputGuardrail;
    private final HandoffDetector handoffDetector;

    public SupportService(
            ChatClient.Builder builder,
            QuestionAnswerAdvisor ragAdvisor,
            PerformanceLoggingAdvisor performanceAdvisor,
            ObjectMapper objectMapper,
            OrderTools orderTools,
            InputGuardrailAdvisor inputGuardrail,
            HandoffDetector handoffDetector
    ) {
        // ragAdvisor(order=20) → performanceAdvisor(order=100). 이 ChatClient 는 Memory 가 없는
        // 단발 호출용(/support 구조화, /chat, /chat/stream)이라 RAG 가 매 요청 질문을 그대로 검색한다.
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(ragAdvisor, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
        this.objectMapper = objectMapper;
        // InputGuardrail/Handoff 는 Advisor 체인에 넣지 않고 검사 메서드만 빌려 쓴다.
        // short-circuit 평문 응답이 .entity(SupportResponse) JSON 파싱을 깨뜨리므로,
        // /support 는 LLM 호출 전 선검사 후 SupportResponse 를 수동 조립하는 방식으로 차단/전환한다.
        this.inputGuardrail = inputGuardrail;
        this.handoffDetector = handoffDetector;
    }

    /** (B) 구조화 모드 — JSON 12필드. `/api/v1/support`. */
    public SupportResponse generateSupportResponse(String message) {
        GuardrailResult guard = inputGuardrail.check(message);
        if (!guard.allowed()) {
            return blockedResponse(guard);
        }
        HandoffDetector.HandoffDecision handoff = handoffDetector.detect(message);
        if (handoff.handoff()) {
            return handoffResponse(handoff);
        }
        return chatClient
                .prompt()
                .user(message)
                .call()
                .entity(SupportResponse.class);
    }

    /**
     * 입력 차단 시 LLM 호출 없이 스키마에 맞는 SupportResponse 를 수동 조립한다.
     * 평문 short-circuit 이 .entity() 파싱을 깨는 함정을 피하면서 구조화 계약을 지킨다.
     * confidenceLevel=HIGH (규칙 기반 차단이라 확신), recommendedRouting=AUTO (후속 처리 없음).
     */
    private SupportResponse blockedResponse(GuardrailResult guard) {
        return new SupportResponse(
                "입력 Guardrail 차단(" + guard.reason() + ") — LLM 호출 없이 반환됨.",
                guard.fallbackMessage(),
                SupportResponse.Category.ETC,
                SupportResponse.Intent.ETC_OTHER,
                List.of(),
                SupportResponse.Urgency.NORMAL,
                "차단된 입력 — 추가 처리 없음.",
                List.of(),
                List.of(),
                0,
                SupportResponse.ConfidenceLevel.HIGH,
                SupportResponse.RecommendedRouting.AUTO
        );
    }

    /**
     * 상담원 전환 시 LLM 호출 없이 스키마에 맞는 SupportResponse 를 수동 조립한다.
     * quest 규약: Category=ETC, Urgency=HIGH, nextAction="상담원 연결 진행".
     * 후속 책임 주체가 상담원이므로 recommendedRouting=AGENT_REVIEW.
     */
    private SupportResponse handoffResponse(HandoffDetector.HandoffDecision handoff) {
        return new SupportResponse(
                "상담원 전환(" + handoff.reason() + ") — LLM 호출 없이 반환됨.",
                handoff.message(),
                SupportResponse.Category.ETC,
                SupportResponse.Intent.ETC_OTHER,
                List.of(),
                SupportResponse.Urgency.HIGH,
                "상담원 연결 진행",
                List.of(),
                List.of(),
                0,
                SupportResponse.ConfidenceLevel.HIGH,
                SupportResponse.RecommendedRouting.AGENT_REVIEW
        );
    }

    /** (A) 자유 텍스트 모드 — 동기. `/api/v1/chat`. */
    public String chat(String message) {
        GuardrailResult guard = inputGuardrail.check(message);
        if (!guard.allowed()) {
            return guard.fallbackMessage();
        }
        return chatClient
                .prompt()
                .user(message)
                .call()
                .content();
    }

    /** (A) 자유 텍스트 모드 — 스트리밍. `/api/v1/chat/stream` 의 token 부분. */
    public Flux<String> streamSupportResponse(String message) {
        GuardrailResult guard = inputGuardrail.check(message);
        if (!guard.allowed()) {
            return Flux.just(guard.fallbackMessage());
        }
        return chatClient
                .prompt()
                .user(message)
                .stream()
                .content();
    }

    /**
     * SSE 옵션 A — 자유 텍스트 streaming + 마지막에 구조화 메타데이터 한 번.
     * - event: token  : (A) 자유 텍스트 토큰
     * - event: meta   : (B) 구조화 JSON 12필드 (별도 동기 호출)
     * Trade-off: LLM 호출 2회 (streaming + structured) → 비용 ×2.
     */
    public Flux<ServerSentEvent<String>> streamSupportWithMetadata(String message) {
        Flux<ServerSentEvent<String>> tokens = streamSupportResponse(message)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(chunk)
                        .build());

        Mono<ServerSentEvent<String>> meta = Mono.fromCallable(() -> {
                    SupportResponse classified = generateSupportResponse(message);
                    return ServerSentEvent.<String>builder()
                            .event("meta")
                            .data(objectMapper.writeValueAsString(classified))
                            .build();
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(tokens, meta);
    }
}
