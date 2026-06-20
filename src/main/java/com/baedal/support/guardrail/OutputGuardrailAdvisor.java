package com.baedal.support.guardrail;

import com.baedal.support.observability.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Round 5 — 출력 단 Guardrail (order=50).
 *
 * <p>{@code chain.nextCall()}로 모델 응답을 받은 뒤, 이 순서로 가공한다.
 * <ol>
 *   <li>시스템 프롬프트 구조 마커({@link #LEAK_MARKERS})가 보이면 응답 전체를 {@link #LEAK_FALLBACK}로 치환</li>
 *   <li>민감 정보가 있으면 {@link SensitiveDataMasker#mask}로 치환</li>
 *   <li>그 결과가 비면 {@link #EMPTY_FALLBACK}로 치환</li>
 * </ol>
 *
 * <p>order=50으로 performance(100)보다 바깥에 둔다. performance가 모델 바로 앞(안쪽)에서
 * 날 응답의 토큰·시간을 먼저 재고, 그 뒤에 이 advisor가 응답을 다시 쓴다. 순서를 뒤집어
 * 마스킹을 performance보다 안쪽에 두면 performance가 가공된 응답을 재게 되는데, 그 관찰은 docs/5주차/02.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputGuardrailAdvisor implements CallAdvisor {

    /** 시스템 프롬프트가 응답으로 새어 나왔는지 알려주는 구조 마커(섹션 헤더). */
    private static final List<String> LEAK_MARKERS = List.of(
            "[역할]", "[규칙]", "[금지]", "[Tool 사용 규칙]", "[응답 톤]", "[정책 인용 규칙]", "[응답 포맷]");

    private static final String LEAK_FALLBACK =
            "요청하신 내용은 안내해 드릴 수 없습니다. 주문·배달·환불 관련 문의를 도와드리겠습니다.";

    private static final String EMPTY_FALLBACK =
            "죄송합니다. 답변을 생성하지 못했습니다. 상담원 연결로 도와드리겠습니다.";

    private final SensitiveDataMasker masker;
    private final AgentMetrics metrics;

    @Override
    public String getName() {
        return "OutputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        String original = extractText(response);
        if (original == null) {
            return response;
        }

        String transformed = transform(original);
        if (transformed.equals(original)) {
            return response;
        }

        // dev-only: 원본↔최종 대조용 DEBUG. 평문 PII가 로그에 남으므로 운영에서는 꺼야 한다
        // (4단계 AI 코드 리뷰의 "마스킹 전 평문 로그" 결함과 같은 자리).
        log.debug("[OutputGuardrail] 원본='{}' → 최종='{}'", original, transformed);
        return rebuild(response, transformed);
    }

    /** 유출 마커 → 민감정보 마스킹 → 빈 응답 순으로 가공한다. */
    String transform(String text) {
        if (containsLeakMarker(text)) {
            metrics.guardrailBlock("output", "PROMPT_LEAK");
            log.warn("[OutputGuardrail] PROMPT_LEAK 차단 — 시스템 프롬프트 마커 노출");
            return LEAK_FALLBACK;
        }
        String masked = text;
        if (masker.containsSensitive(text)) {
            masked = masker.mask(text);
            metrics.guardrailBlock("output", "SENSITIVE_MASKED");
            log.info("[OutputGuardrail] SENSITIVE_MASKED — 민감 정보 마스킹 적용");
        }
        if (masked.isBlank()) {
            metrics.guardrailBlock("output", "EMPTY_RESPONSE");
            log.warn("[OutputGuardrail] EMPTY_RESPONSE — 빈 응답 Fallback");
            return EMPTY_FALLBACK;
        }
        return masked;
    }

    private boolean containsLeakMarker(String text) {
        for (String marker : LEAK_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String extractText(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    /**
     * 가공한 텍스트로 응답을 다시 만든다. {@code ChatResponse.builder().from(...)}으로
     * usage 메타데이터를 보존해, 바깥 advisor가 토큰을 읽어도 손실이 없게 한다.
     */
    private ChatClientResponse rebuild(ChatClientResponse response, String text) {
        ChatResponse original = response.chatResponse();
        Generation generation = new Generation(
                new AssistantMessage(text), original.getResult().getMetadata());
        ChatResponse rebuilt = ChatResponse.builder()
                .from(original)
                .generations(List.of(generation))
                .build();
        return response.mutate().chatResponse(rebuilt).build();
    }
}
