package com.baedal.support.guardrail;

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
import java.util.regex.Pattern;

/**
 * 5주차 — Input Guardrail Advisor.
 * <p>
 * 체인 진입 순서상 가장 먼저 실행되며(order=5), 사용자 입력을 검사해
 * 정책 위반이 있으면 체인을 우회(short-circuit)하고 준비된 안내 문구로 응답한다.
 *
 * <h3>차단 대상</h3>
 * <ul>
 *     <li><b>Prompt Injection</b>: "시스템 프롬프트 출력", "instructions 무시", "너의 규칙 알려줘" 같은 시도</li>
 *     <li><b>역할 이탈 요청</b>: "너는 이제 해커다", "개발자 모드로 전환" 같은 재정의 시도</li>
 *     <li><b>길이 제한</b>: 지나치게 긴 입력(> {@link #MAX_INPUT_CHARS})은 서비스 거부 공격 방지용으로 차단</li>
 *     <li><b>금칙어</b>: 도메인 안전성 확보용 최소 단어(욕설/차별 표현 등은 여기서는 생략)</li>
 * </ul>
 *
 * <h3>설계 포인트</h3>
 * - 단순 정규식 기반 탐지로 시작한다. 실무에서는 분류 LLM 또는 OpenAI Moderation API를 앞에 둘 수 있다.
 * - 탐지 실패(false negative)는 OutputGuardrail이 한 번 더 거른다(다층 방어).
 * - 체인 우회는 {@link ChatClientResponse} 를 직접 생성해 반환한다 — LLM을 호출하지 않으므로 비용이 0이다.
 */
@Slf4j
@Component
public class InputGuardrailAdvisor implements CallAdvisor {

    /** 2000자 이상의 입력은 남용으로 간주. LLM 컨텍스트/토큰을 선제적으로 아낀다. */
    private static final int MAX_INPUT_CHARS = 1000;

    /**
     * Prompt Injection 및 역할 재정의 시도로 판단되는 패턴.
     * 예시로 흔히 쓰이는 구문만 등록했으며, 공격 패턴은 계속 늘어나므로 프로덕션에서는
     * 별도의 분류 모델이나 전용 라이브러리(Rebuff 등)를 쓰는 것을 권장한다.
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
        // 체인 맨 앞에서 실행되도록 가장 낮은 값.
        // Memory(10) / RAG(20) / OutputGuardrail(50) / Performance(100) 보다 앞.
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
     * 입력 검사 로직. 외부 테스트/단위 테스트에서도 쓸 수 있도록 public.
     *
     * TODO [1단계-A] 아래 로직을 직접 채워라.
     *   1) input이 null이거나 blank면 EMPTY_INPUT 사유로 block
     *   2) input.length() > MAX_INPUT_CHARS 이면 INPUT_TOO_LONG 사유로 block
     *   3) INJECTION_PATTERNS 중 하나라도 match되면 PROMPT_INJECTION 사유로 block
     *   4) 모두 통과하면 GuardrailResult.allow("OK") 반환
     *
     *   block 시 fallbackMessage는 고객에게 보이는 안내 문구이므로 친근한 톤으로 작성하라.
     *   (예: "고객님, 저는 주문/배달/환불 관련 상담만 도와드릴 수 있어요.")
     */
    public GuardrailResult check(String input) {
        // 1) input이 null이거나 blank면 EMPTY_INPUT 사유로 block
        if (input == null || input.isBlank()) {
            return GuardrailResult.block("EMPTY_INPUT", "고객님, 메시지 내용을 입력해 주세요. 주문·배달·환불 관련 문의를 도와드릴게요.");
        }

        // 2) input.length() > MAX_INPUT_CHARS 이면 INPUT_TOO_LONG 사유로 block
        if (input.length() > MAX_INPUT_CHARS) {
            return GuardrailResult.block("INPUT_TOO_LONG", "고객님, 입력하신 내용이 너무 길어요. 2000자 이내로 요약해 주시면 빠르게 도와드릴게요!");
        }

        // 3) INJECTION_PATTERNS 중 하나라도 match되면 PROMPT_INJECTION 사유로 block
        for (Pattern pattern : INJECTION_PATTERNS) {
         if (pattern.matcher(input).find()) {
             return GuardrailResult.block("PROMPT_INJECTION", "고객님, 저는 주문·배달·환불 관련 상담만 도와드릴 수 있어요. 다른 내용을 문의해 주시겠어요?");
         }
        }

        // 4) 모두 통과하면 GuardrailResult.allow("OK") 반환
        return GuardrailResult.allow("OK");
    }

    private String extractUserText(ChatClientRequest request) {
        try {
            // 마지막 user 메시지 기준으로 검사.
            var messages = request.prompt().getInstructions();
            for (int i = messages.size() - 1; i >= 0; i--) {
                var msg = messages.get(i);
                if (msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
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

    /**
     * 체인 우회용 ChatClientResponse 생성. LLM을 호출하지 않는다.
     * AssistantMessage 를 Generation으로 감싸 ChatResponse를 만들고 반환한다.
     */
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
