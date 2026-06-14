package com.baedal.support.guardrail;

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
 * 5주차 — Output Guardrail Advisor (order=50).
 * <p>
 * LLM 응답이 돌아온 뒤 마지막으로 검사한다. Input Guardrail 이 놓친 것(FN),
 * LLM 이 확률적으로 새어나가게 한 것을 출력 단에서 다시 거른다(다층 방어).
 *
 * <h3>수행 작업 (우선순위)</h3>
 * <ol>
 *     <li><b>빈 응답</b>: null/blank 면 {@link #EMPTY_FALLBACK} 로 대체</li>
 *     <li><b>시스템 프롬프트 유출</b>: 응답에 내부 섹션명({@link #LEAK_MARKERS})이 보이면 {@link #LEAK_FALLBACK} 로 통째 치환</li>
 *     <li><b>민감 정보</b>: 전화/이메일/주소가 있으면 {@link SensitiveDataMasker} 로 값만 마스킹</li>
 * </ol>
 *
 * <h3>왜 order=50 인가</h3>
 * InputGuardrail(5) → Memory(10) → RAG(20) → (LLM 호출) → <b>OutputGuardrail(50)</b> → Performance(100).
 * 응답은 체인을 거슬러 올라오며 가공되므로, OutputGuardrail 이 Performance(100)보다 안쪽(작은 order)에
 * 있어야 Performance 로깅에 "이미 마스킹된" 응답/토큰이 찍힌다(평문이 로그에 남지 않음). 반면 Memory/RAG
 * 보다는 바깥이어야 그들이 조립한 프롬프트가 LLM 을 왕복한 "뒤" 최종 출력만 검사한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputGuardrailAdvisor implements CallAdvisor {

    /** 시스템 프롬프트 내부 섹션명이 응답에 그대로 보이면 유출 — 통째 Fallback. (BaedalPrompt 실제 섹션에 맞춤) */
    private static final List<String> LEAK_MARKERS = List.of(
            "[역할]", "[규칙]", "[금지]", "[Tool 사용 규칙]", "[정책 인용 규칙]",
            "[분류 가이드]", "[응답 작성 가이드]", "[정보 수집 가이드]", "[보상 처리 가이드]"
    );

    private static final String LEAK_FALLBACK =
            "고객님, 저는 주문/배달/환불 관련 상담을 도와드리고 있어요. 궁금하신 내용을 알려주세요.";

    private static final String EMPTY_FALLBACK =
            "죄송해요, 답변을 준비하는 데 어려움이 있었습니다. 다시 한 번 말씀해 주시거나 상담원 연결을 원하시면 '상담원'이라고 입력해 주세요.";

    private final SensitiveDataMasker masker;

    @Override
    public String getName() {
        return "OutputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        // Memory(10)/RAG(20)보다 바깥, Performance(100)보다 안쪽.
        return 50;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);   // 먼저 LLM 까지 다 실행
        String content = extractContent(response);

        if (content == null || content.isBlank()) {
            return replace(response, request, EMPTY_FALLBACK, "EMPTY_RESPONSE");
        }
        for (String marker : LEAK_MARKERS) {
            if (content.contains(marker)) {
                return replace(response, request, LEAK_FALLBACK, "PROMPT_LEAK");
            }
        }
        if (masker.containsSensitive(content)) {
            String masked = masker.mask(content);
            log.debug("[OutputGuardrail] 원본='{}' → 마스킹='{}'", content, masked);
            return replace(response, request, masked, "SENSITIVE_MASKED");
        }
        return response;
    }

    private String extractContent(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) return null;
        var chat = response.chatResponse();
        if (chat.getResult() == null || chat.getResult().getOutput() == null) return null;
        return chat.getResult().getOutput().getText();
    }

    /**
     * 응답 내용을 치환한 새 ChatClientResponse 생성. 기존 metadata(토큰 등)는 유지하고 Generation 만 교체한다.
     */
    private ChatClientResponse replace(ChatClientResponse original, ChatClientRequest request,
                                       String newText, String reason) {
        log.warn("[OutputGuardrail] 응답 치환 — reason={}", reason);
        Generation generation = new Generation(new AssistantMessage(newText));
        ChatResponse.Builder builder = ChatResponse.builder().generations(List.of(generation));
        if (original != null && original.chatResponse() != null
                && original.chatResponse().getMetadata() != null) {
            builder.metadata(original.chatResponse().getMetadata());
        }
        return ChatClientResponse.builder()
                .chatResponse(builder.build())
                .context(request.context())
                .build();
    }
}
