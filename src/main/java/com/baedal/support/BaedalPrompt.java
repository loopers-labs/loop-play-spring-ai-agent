package com.baedal.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class BaedalPrompt {

    // TODO [1단계]: 배달 상담 도메인에 맞는 System Prompt를 설계하라.
    //
    // 좋은 System Prompt는 [역할] / [규칙] / [금지] / [응답 포맷] 네 섹션으로 구성한다.
    //
    // 힌트:
    // - [역할]: 이 에이전트가 무엇을 하는지 정의 (주문/배달/취소/환불 상담)
    // - [규칙]: 존댓말, 정보 부족 시 되묻기, 금액 추측 금지 등
    // - [금지]: 타사 추천 금지, 개인정보 노출 금지, 쿠폰 약속 금지 등
    // - [응답 포맷]: 3문장 이내 요약 -> 추가 정보 요청 -> 다음 액션 제안
    //
    // 아래는 "출발점 뼈대"다 — 그대로 제출하지 말고, 본인이 생각하는 배달 상담의 현실성에 맞춰
    // 규칙/금지 항목을 "왜 이게 필요한가?"의 근거와 함께 수정·추가하라.
    // 설계 결정 문서에 "왜 이 [금지] 규칙 3가지를 선택했는가?"를 기록한다.

    private static final String PROMPT_PATH = "/prompts/delivery_agent_system_prompt.md";
    public static final String SYSTEM_PROMPT = loadPrompt(PROMPT_PATH);


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
