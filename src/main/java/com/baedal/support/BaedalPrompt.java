package com.baedal.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 [Tool 사용 규칙]
 - 주문 상세, 배달 현황, 주문 취소는 반드시 제공된 Tool을 통해서만 수행합니다.
 (절대로 값을 추측하거나 상상하지 않습니다.)
 - getOrderDetail: 고객이 메뉴/금액/상태를 물을 때 사용합니다.
 - getDeliveryStatus: 고객이 "어디쯤 있어요?", "언제 와요?"를 물을 때 사용합니다.
 - cancelOrder: 고객이 명시적으로 취소를 요청할 때만 호출합니다.
 Tool 결과의 outcome 필드를 보고 고객에게 맞게 설명합니다.
 - Tool이 null을 돌려주면 "해당 주문번호를 찾을 수 없다"고 안내합니다.

 [대화 맥락 사용 규칙]
 - 이전 대화에서 고객이 주문번호를 언급했다면, "그거", "방금 그 주문", "아까 말한 주문" 같은
 지시 대명사는 가장 최근에 언급된 주문번호로 해석합니다.
 - 맥락상 여러 주문번호가 언급되었다면, 가장 마지막에 언급된 주문번호를 우선 사용합니다.
 - 맥락이 모호하면 추측하지 말고 "어떤 주문을 말씀하시는 건가요?"라고 다시 확인합니다.
 - 이전 턴에서 이미 Tool로 조회한 정보는 다시 Tool을 호출하지 말고 대화 이력에서 재사용합니다.

 [정책 인용 규칙]
 - TODO: 이 섹션을 채워라. (위 TODO [1단계-J] 주석 참고)
 - TODO: Fallback 문구 / 수치 원문 유지 / 상담 범위 밖 대응 / 복수 정책 우선순위를 규칙으로 작성

 [응답 포맷]
 - 3문장 이내로 요약 → 필요한 추가 정보 요청 → 다음 액션 제안
 """;
 */

// TODO [1단계-J] SYSTEM_PROMPT에 [정책 인용 규칙] 섹션을 추가하라.
//
// 아래 SYSTEM_PROMPT에는 [Tool 사용 규칙]과 [대화 맥락 사용 규칙]까지만 있다 (3주차까지).
// 4주차에는 그 뒤에 [정책 인용 규칙] 섹션을 추가해야 RAG의 Context 블록을
// LLM이 어떻게 사용할지 제어할 수 있다.
//
// 아래 "TODO: [정책 인용 규칙]" 주석 자리에 다음과 같은 규칙을 직접 작성해 넣어라.
//   - Context에서 답을 찾지 못하면 어떤 문구로 답할지 (Fallback 문구 고정)
//   - 정책의 수치/조건을 임의로 반올림·단순화하지 않는다
//   - 상담 범위 밖 질문(예: "오늘 점심 추천")에는 Context를 인용하지 않고 범위 안내
//   - 여러 정책이 관련될 때 고객 상황에 맞는 것을 우선 선택
//
// 설계 결정 질문 (README):
//   - "similarityThreshold로 거르면 되는 거 아닌가?" — 왜 Fallback 문구를 프롬프트에도 박아야 하는가?
//     (힌트: 임계값은 "관련 없는 문서 제거"만 한다. "LLM이 없는 얘기를 지어내는 것"은 막지 못한다.)
//   - 상담 범위 밖 질문 대응 문구를 프롬프트에 고정하면 Fallback이 LLM의 판단과 독립적으로 일관된다.
//     이 문구를 바꿀 때 실제 고객 경험이 어떻게 달라질까?
public final class BaedalPrompt {


    private static final String PROMPT_PATH = "/prompts/delivery_agent_system_prompt.md";
    public static final String SYSTEM_PROMPT = loadPrompt(PROMPT_PATH);

    // AssistantController(Tool Calling 흐름 관찰) 전용 프롬프트.
    // JSON 응답 강제 없이 Tool을 자유롭게 호출할 수 있도록 별도로 관리한다.
    private static final String ASSISTANT_PROMPT_PATH = "/prompts/assistant_system_prompt.md";
//    private static final String ASSISTANT_PROMPT_PATH = "/prompts/assistant_system_prompt_without_tool_rule.md";
    public static final String ASSISTANT_SYSTEM_PROMPT = loadPrompt(ASSISTANT_PROMPT_PATH);
    // 이 프롬프트에 대응하는 평가 기준.
    // 프롬프트의 [응답 형식] / [금지 사항] 섹션과 반드시 동기화하여 관리한다.
    public static final EvalCriteria EVAL_CRITERIA = new EvalCriteria(
            Set.of("주문 상태 조회 요청", "추가 정보 확인", "상담사 연결", "안내 완료"),
            List.of(
                    "쿠팡이츠", "요기요", "배달통",
                    "환불해드리겠습니다", "쿠폰 드리겠습니다", "발급해드리겠습니다",
                    "연락처는", "전화번호는", "주소는",
                    "오늘 안에 처리됩니다", "3일 이내에 환불됩니다"
            ),
            Map.of("주문 상태 조회 요청", "주문번호")
    );

    private static String loadPrompt(String path) {
        try (InputStream in = BaedalPrompt.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Prompt resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt: " + path, e);
        }
    }
}
