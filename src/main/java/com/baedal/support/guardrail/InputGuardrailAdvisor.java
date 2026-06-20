package com.baedal.support.guardrail;

import com.baedal.support.observability.AgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Round 5 — 입력 단 Guardrail (Advisor 체인의 가장 바깥, order=5).
 *
 * <p>빈 입력 / 길이 초과 / Prompt Injection 패턴을 <b>LLM 호출 전에</b> short-circuit 으로 잘라낸다.
 * {@link #adviseCall}이 {@code chain.nextCall()}을 부르지 않고 바로 fallback 응답을 만들어 돌려주므로
 * Memory(10)·RAG(20)·Model 어느 것도 깨어나지 않는다 → 차단 트래픽의 LLM 비용은 0 이다.</p>
 *
 * <p>order=5 로 Memory(10)보다 앞에 둔 이유: 차단해야 할 발화가 Memory 에 먼저 저장되면
 * "injection 시도"가 대화 이력으로 굳어 다음 턴 프롬프트에 다시 끼어든다. 가장 바깥에서 걸러야
 * 오염 자체가 없다. (관찰 기록은 docs/5주차/01 참조)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InputGuardrailAdvisor implements CallAdvisor {

    private final AgentMetrics metrics;

    /**
     * 입력 최대 길이. 배달 상담 한 건의 발화는 길어야 수백 자라, 2000자면 상세한 불만 진술도 담긴다.
     * 너무 낮으면(예: 200) 정상 장문 문의가 거짓 차단(FP)되고,
     * 너무 높으면(예: 50000) injection 페이로드 stuffing 과 임베딩·프롬프트 토큰 폭증(DoS)을 못 막는다.
     * 근거와 트레이드오프는 docs/5주차/01 의 "설계 결정"에 적었다.
     */
    static final int MAX_INPUT_CHARS = 2000;

    /**
     * Prompt Injection 대표 패턴. 한국어 띄어쓰기/어미 변형을 일부 흡수하도록 느슨하게 잡되,
     * 정상 상담어("규칙이 어떻게 되나요")까지 잡지 않도록 "무시/잊어/우회/유출" 같은 공격 동사와의
     * 동시 출현을 요구한다. 정규식만으로 막는 한계(FP/FN)는 docs/5주차/01 에 분리해 적었다.
     */
    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // "이전 지시 무시", "위 명령 잊어", "모든 규칙을 무시" — 지시류 + 무력화 동사
            Pattern.compile("(이전|위|앞|모든|기존|상위)\\s*[^\\n]{0,12}(지시|명령|규칙|프롬프트|설정)[^\\n]{0,15}(무시|잊|어기|벗어)", CI),
            // "시스템 프롬프트 출력/유출/공개/보여줘"
            Pattern.compile("(시스템\\s*프롬프트|system\\s*prompt)[^\\n]{0,20}(출력|보여|알려|공개|유출|복사|그대로|reveal|show|print|dump)", CI),
            // "개발자 모드", "관리자 모드", "developer mode", "DAN", "jailbreak"
            Pattern.compile("(개발자\\s*모드|관리자\\s*모드|developer\\s*mode|jailbreak|\\bDAN\\b)", CI),
            // 영어 표준 injection: "ignore (the) (previous/all/prior) ... instructions/prompt/rules"
            Pattern.compile("ignore\\s+[^\\n]{0,25}(instruction|prompt|rule|guideline)", CI),
            // "규칙을 무시", "제약을 무시" 단독형 (지시류 단어 없이도)
            Pattern.compile("(규칙|제약|제한|가이드라인)[을를]?\\s*[^\\n]{0,10}(무시|해제|풀어|없애)", CI)
    );

    @Override
    public String getName() {
        return "InputGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String input = extractUserText(request);
        Decision decision = check(input);
        if (decision.blocked()) {
            metrics.guardrailBlock("input", decision.reason());
            log.warn("[InputGuardrail] 차단 — reason={} inputLen={}",
                    decision.reason(), input == null ? 0 : input.length());
            return shortCircuit(request, decision.fallbackMessage());
        }
        return chain.nextCall(request);
    }

    /**
     * 입력 검사. 빈 입력 → 길이 초과 → injection 패턴 순으로 본다.
     * 가장 싼 검사(길이/공백)를 앞에 두어 정규식 매칭 비용을 아낀다.
     */
    Decision check(String input) {
        if (input == null || input.isBlank()) {
            return Decision.blocked("EMPTY_INPUT",
                    "문의 내용이 비어 있습니다. 어떤 점이 궁금하신지 입력해 주세요.");
        }
        if (input.length() > MAX_INPUT_CHARS) {
            return Decision.blocked("INPUT_TOO_LONG",
                    "문의가 너무 깁니다. " + MAX_INPUT_CHARS + "자 이내로 핵심만 작성해 주세요.");
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return Decision.blocked("PROMPT_INJECTION",
                        "해당 요청은 처리할 수 없습니다. 주문·배달·환불 관련 문의를 남겨 주시면 도와드리겠습니다.");
            }
        }
        return Decision.pass();
    }

    private String extractUserText(ChatClientRequest request) {
        UserMessage userMessage = request.prompt().getUserMessage();
        return userMessage == null ? null : userMessage.getText();
    }

    /**
     * chain.nextCall() 을 호출하지 않고 fallback 메시지만 담은 응답을 만든다.
     * 이 응답에는 LLM usage 메타데이터가 없으므로 PerformanceLoggingAdvisor 도 토큰을 못 찍는다
     * (= 차단 트래픽 LLM 비용 0 의 증거).
     */
    private ChatClientResponse shortCircuit(ChatClientRequest request, String fallbackMessage) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(fallbackMessage))));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    /** 입력 검사 결과. blocked=true 면 reason/fallbackMessage 가 채워진다. */
    record Decision(boolean blocked, String reason, String fallbackMessage) {
        static Decision pass() {
            return new Decision(false, null, null);
        }

        static Decision blocked(String reason, String fallbackMessage) {
            return new Decision(true, reason, fallbackMessage);
        }
    }
}
