package com.baedal.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private BaedalPrompt() {}

    private static String loadPrompt(String path) {
        try (InputStream in = BaedalPrompt.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Prompt resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt: " + path, e);
        }
    }
}
