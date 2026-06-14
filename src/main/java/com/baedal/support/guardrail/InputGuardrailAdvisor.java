package com.baedal.support.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 5주차 — Input Guardrail Advisor (체인 맨 앞, order=5).
 * <p>
 * 사용자 입력을 LLM 에 닿기 <b>전에</b> 검사해 정책 위반이면 체인을 우회(short-circuit)하고
 * 준비된 안내 문구로 응답한다. {@code chain.nextCall()} 을 호출하지 않으면 Memory·RAG·LLM 이
 * 전혀 안 돌아 공격 입력의 토큰 비용이 0 이 된다 — DoS 관점에서 맨 앞에 두는 유일한 이유.
 *
 * <h3>차단 대상</h3>
 * <ul>
 *     <li><b>Prompt Injection</b>: "시스템 프롬프트 출력", "이전 지시 무시", "너의 규칙 알려줘"</li>
 *     <li><b>역할 이탈 요청</b>: "너는 이제 …", "개발자 모드로 전환" 같은 재정의 시도</li>
 *     <li><b>길이 제한</b>: {@link #MAX_INPUT_CHARS} 초과 입력은 DoS 방지용으로 차단</li>
 *     <li><b>빈 입력</b>: null/blank 는 LLM 호출 가치가 없으므로 차단</li>
 * </ul>
 *
 * <h3>왜 order=5 (Memory=10 보다 앞) 인가</h3>
 * Memory(10)/RAG(20) 가 프롬프트를 조립하기 전에 차단해야 (1) 불필요한 토큰이 안 쌓이고
 * (2) 공격 입력이 대화 이력(ChatMemory)에 저장되지 않는다. 뒤에 두면 차단해도 이미
 * 임베딩 검색·이력 적재가 끝난 뒤라 "비용 0" 전제가 깨진다.
 *
 * <h3>왜 정규식인가 (한계 포함)</h3>
 * 분류 LLM / Moderation API 대비 비용·지연 0 이라 교육 단계 1차 방어로 적합하다. 단 공백·
 * 제로너비 문자·번역 우회로 새 패턴이 계속 생기므로(FN) 완벽할 수 없고, 정상 문장을 공격으로
 * 오탐(FP)할 수도 있다. 탐지 실패는 OutputGuardrail 이 한 번 더 거른다(다층 방어).
 */
@Slf4j
@Component
public class InputGuardrailAdvisor implements CallAdvisor {

    /** 2000자 이상 입력은 남용으로 간주. 너무 낮으면 정상 장문 문의를 끊고, 너무 높으면 DoS 방어가 무력해진다. */
    private static final int MAX_INPUT_CHARS = 2000;

    private static final String REASON_EMPTY = "EMPTY_INPUT";
    private static final String REASON_TOO_LONG = "INPUT_TOO_LONG";
    private static final String REASON_INJECTION = "PROMPT_INJECTION";

    private static final String FALLBACK_EMPTY =
            "문의 내용이 비어 있어요. 어떤 점을 도와드릴지 입력해 주세요.";
    private static final String FALLBACK_TOO_LONG =
            "문의가 너무 길어서 처리할 수 없어요. 핵심 내용만 짧게 다시 보내주세요.";
    private static final String FALLBACK_INJECTION =
            "고객님, 저는 주문·배달·환불 관련 상담만 도와드릴 수 있어요. 무엇을 도와드릴까요?";

    /**
     * Prompt Injection 및 역할 재정의 시도 패턴.
     * 흔한 구문만 등록했으며, 공격 패턴은 계속 늘어나므로 프로덕션에서는 분류 모델이나
     * 전용 라이브러리(Rebuff 등)를 권장한다. 정상 상담 문장은 어디에도 걸리지 않아야 한다(FP 방지).
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)(system\\s*prompt|시스템\\s*프롬프트|프롬프트\\s*출력|프롬프트\\s*보여)"),
            Pattern.compile("(?i)(ignore\\s+(all\\s+)?(previous|above|prior)\\s+instructions|이전\\s*지시\\s*무시)"),
            Pattern.compile("(?i)(jailbreak|DAN\\s*mode|developer\\s*mode|개발자\\s*모드)"),
            Pattern.compile("(?i)(너는\\s*이제|now\\s+you\\s+are|역할을\\s*(바꿔|변경))"),
            Pattern.compile("(?i)(너의\\s*규칙|your\\s+rules|reveal\\s+your\\s+instructions|instructions\\s+reveal)")
    );

    @Override
    public String getName() {
        return "InputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        // 체인 맨 앞: Memory(10) / RAG(20) / OutputGuardrail(50) / Performance(100) 보다 앞.
        return 5;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userInput = extractUserText(request);
        GuardrailResult result = check(userInput);

        if (!result.allowed()) {
            log.warn("[InputGuardrail] 차단 — reason={} | input.len={}", result.reason(),
                    userInput == null ? 0 : userInput.length());
            return shortCircuit(request, result.fallbackMessage());
        }

        return chain.nextCall(request);
    }

    /**
     * 입력 검사 — 빈 입력 / 길이 초과 / Injection 순으로 판별한다.
     * Advisor 와 /support 선검사가 같은 규칙을 공유하도록 public.
     */
    public GuardrailResult check(String input) {
        if (input == null || input.isBlank()) {
            return GuardrailResult.block(REASON_EMPTY, FALLBACK_EMPTY);
        }
        if (input.length() > MAX_INPUT_CHARS) {
            return GuardrailResult.block(REASON_TOO_LONG, FALLBACK_TOO_LONG);
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return GuardrailResult.block(REASON_INJECTION, FALLBACK_INJECTION);
            }
        }
        return GuardrailResult.allow("OK");
    }

    private String extractUserText(ChatClientRequest request) {
        try {
            // 마지막 user 메시지 기준으로 검사.
            var messages = request.prompt().getInstructions();
            for (int i = messages.size() - 1; i >= 0; i--) {
                var msg = messages.get(i);
                if (msg.getMessageType() == MessageType.USER) {
                    return msg.getText();
                }
            }
            return request.prompt().getUserMessage() != null
                    ? request.prompt().getUserMessage().getText()
                    : "";
        } catch (Exception e) {
            log.debug("[InputGuardrail] 사용자 입력 추출 실패 — {}", e.getMessage());
            return "";
        }
    }

    /** 체인 우회용 ChatClientResponse 를 직접 생성. LLM 을 호출하지 않는다. */
    private ChatClientResponse shortCircuit(ChatClientRequest request, String fallbackMessage) {
        AssistantMessage message = new AssistantMessage(fallbackMessage);
        Generation generation = new Generation(message);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(generation))
                .build();
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }
}
