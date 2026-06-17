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
 * 5주차 — Output Guardrail Advisor.
 * <p>
 * LLM 응답이 돌아온 뒤 마지막으로 검사한다. Input Guardrail이 놓친 것,
 * LLM이 확률적으로 새어나가게 한 것들을 여기서 다시 한 번 거른다.
 *
 * <h3>수행 작업</h3>
 * <ol>
 *     <li><b>민감 정보 마스킹</b>: 전화번호/이메일/주소를 {@link SensitiveDataMasker}로 치환</li>
 *     <li><b>시스템 프롬프트 유출 탐지</b>: 응답에 "역할", "규칙", "금지" 같은 내부 섹션 키워드가 보이면 차단</li>
 *     <li><b>Fallback</b>: 응답이 너무 짧거나 비었으면 안내 문구로 대체</li>
 * </ol>
 *
 * <h3>왜 order=50 인가</h3>
 * InputGuardrail(5) → Memory(10) → RAG(20) → (LLM 호출) → <b>OutputGuardrail(50)</b> → Performance(100)
 * <br>
 * 응답 객체는 체인을 "거슬러 올라오며" 가공되므로, OutputGuardrail은 Performance보다 안쪽(작은 order)에
 * 있어야 Performance 로깅에 "이미 마스킹된" 응답이 찍힌다. 반면 Memory/RAG보다는 바깥이어야
 * 그들이 조립한 프롬프트가 LLM을 왕복한 "뒤"에 최종 출력만 검사할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputGuardrailAdvisor implements CallAdvisor {

    /** 시스템 프롬프트 내부 섹션 키워드가 응답에 그대로 보이면 유출 가능성 — 안내 문구로 대체. */
    private static final List<String> LEAK_MARKERS = List.of(
            "[역할]", "[규칙]", "[금지]", "[Tool 사용 규칙]", "[정책 인용 규칙]",
            "[안전 규칙]", "[응답 포맷]", "[대화 맥락 사용 규칙]",
            // 2단계 측정 발견(s5_leak 5/5 유출): LLM이 대괄호 없이 "역할:" 형태로 풀어서 유출 →
            // 콜론 형태 + 고유 섹션명도 탐지. ("규칙:"·"금지:"는 "환불 규칙:" 등 일반어 과탐 위험이라 제외)
            "역할:", "안전 규칙", "Tool 사용 규칙", "정책 인용 규칙", "응답 포맷", "대화 맥락 사용 규칙"
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

    /**
     * 출력 검사: chain.nextCall로 LLM 응답을 받은 뒤
     * ① 빈 응답(EMPTY_FALLBACK) → ② LEAK_MARKERS 포함(PROMPT_LEAK → LEAK_FALLBACK)
     * → ③ 민감정보 포함(SENSITIVE_MASKED → mask) 순으로 검사하고, 문제 없으면 원본을 그대로 반환한다.
     * 치환 시 log.warn으로 사유를 남겨 감사가 가능하게 한다.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);   // 먼저 LLM까지 실행
        String content = extractContent(response);

        // 1) 빈 응답 → 안내 문구
        if (content == null || content.isBlank()) {
            return replace(response, request, EMPTY_FALLBACK, "EMPTY_RESPONSE");
        }
        // 2) 시스템 프롬프트 유출 마커 → 통째 치환 (유출이 더 심각하므로 sensitive보다 먼저)
        for (String marker : LEAK_MARKERS) {
            if (content.contains(marker)) {
                return replace(response, request, LEAK_FALLBACK, "PROMPT_LEAK");
            }
        }
        // 3) 민감정보 → 값만 마스킹
        if (masker.containsSensitive(content)) {
            return replace(response, request, masker.mask(content), "SENSITIVE_MASKED");
        }
        // 4) 문제 없음 → 원본 그대로
        return response;
    }

    private String extractContent(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) return null;
        var chat = response.chatResponse();
        if (chat.getResult() == null || chat.getResult().getOutput() == null) return null;
        return chat.getResult().getOutput().getText();
    }

    /**
     * 응답 내용을 치환한 새 ChatClientResponse 생성.
     * 기존 metadata는 유지하되 Generation만 교체한다.
     */
    private ChatClientResponse replace(ChatClientResponse original, ChatClientRequest request,
                                       String newText, String reason) {
        log.info("[OutputGuardrail] 응답 치환 — reason={}", reason);
        AssistantMessage message = new AssistantMessage(newText);
        Generation generation = new Generation(message);
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
