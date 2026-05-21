package com.baedal.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *   [Tool 사용 규칙]
 *             - 주문 상세, 배달 현황, 주문 취소는 반드시 제공된 Tool을 통해서만 수행합니다.
 *               (절대로 값을 추측하거나 상상하지 않습니다.)
 *             - getOrderDetail: 고객이 메뉴/금액/상태를 물을 때 사용합니다.
 *             - getDeliveryStatus: 고객이 "어디쯤 있어요?", "언제 와요?"를 물을 때 사용합니다.
 *             - cancelOrder: 고객이 명시적으로 취소를 요청할 때만 호출합니다.
 *               Tool 결과의 outcome 필드를 보고 고객에게 맞게 설명합니다.
 *             - Tool이 null을 돌려주면 "해당 주문번호를 찾을 수 없다"고 안내합니다.
 *
 *             [응답 포맷]
 *             - 3문장 이내로 요약 → 필요한 추가 정보 요청 → 다음 액션 제안
 */
public final class BaedalPrompt {


    private static final String PROMPT_PATH = "/prompts/delivery_agent_system_prompt.md";
    public static final String SYSTEM_PROMPT = loadPrompt(PROMPT_PATH);
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
