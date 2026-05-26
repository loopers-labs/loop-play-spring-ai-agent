# Step 4 — Observability + System Prompt 2x 실험

`PerformanceLoggingAdvisor` 구현 후 LLM 호출의 elapsed + token 사용량 로깅. `SupportController`와 `PromptLabController`에 `.defaultAdvisors(performanceAdvisor)`로 등록.

---

## PerformanceLoggingAdvisor 구현

`org.springframework.ai.chat.client.advisor.api.CallAdvisor` 구현. `chain.nextCall(request)` 전/후 시간 측정 + `response.chatResponse().getMetadata().getUsage()` 토큰 추출. 방어적 null 체크 + 실패 경로 try-catch.

---

## 시나리오 1 호출 시 토큰 + 시간

**입력**: `"주문번호 2024-1234 배달 어디쯤에 있어요?"` (단계 1 시나리오 1과 동일)

**응답**:
```json
{
  "summary": "배송 진행 상황을 확인한 뒤, 현재 위치를 안내해드리겠습니다.",
  "category": "DELIVERY",
  "urgency": "NORMAL",
  "nextAction": "현재 배달 위치 확인 후 답변 드리겠습니다.",
  "neededInfo": ["주문 상태"],
  "responsibleParties": ["RIDER"],
  "suspicionSignals": []
}
```

**Spring 로그**:
```
[LLM] elapsed=5232ms | promptTokens=873 | completionTokens=85 | totalTokens=958
```

**분해**:
- **promptTokens = 873**: BaedalPrompt SYSTEM_PROMPT(약 493 토큰) + 사용자 메시지(약 20 토큰) + Spring AI `BeanOutputConverter`가 자동 주입하는 JSON schema(약 360 토큰)
- **completionTokens = 85**: SupportResponse JSON 응답
- **elapsed = 5232ms**: warm cache 호출 (cold는 ~50초)

---

## System Prompt 2배 실험

목표: System Prompt 길이가 토큰 사용량과 응답 시간에 미치는 영향 측정.

**방법**: `BaedalPrompt.SYSTEM_PROMPT`를 그대로 한 번 더 이어 붙여 2배 길이로 만든 뒤 같은 시나리오 호출.

| | bytes | promptTokens | elapsed | urgency |
|---|---|---|---|---|
| **1x prompt** | 1856 | **873** | 5232ms | NORMAL |
| **2x prompt** | 3643 | **1366** | **7565ms** | **LOW** |

**관찰**:
- 토큰 차이 = 1366 - 873 = **493** → BaedalPrompt SYSTEM_PROMPT 자체가 약 **493 토큰** 차지
- 입력 토큰 **1.56배** 증가 (2배 byte 증가) → elapsed **44% 증가** (5232 → 7565ms). prompt processing time이 입력 길이에 비례
- **부작용**: 2x에서 urgency `NORMAL` → `LOW`로 분류가 바뀜. 긴 프롬프트가 분류 일관성을 흔들 수 있다는 신호 ("lost-in-the-middle" 가설 / attention 분산)

## 토큰 비용 분해 (이 실험에서 도출)

전체 promptTokens (873) = 
- **BaedalPrompt SYSTEM_PROMPT** ≈ 493 토큰 (2x 실험에서 직접 측정)
- **JSON schema 자동 주입** ≈ 360 토큰 (873 - 493 - 사용자 메시지 약 20)
- **사용자 메시지** ≈ 20 토큰

→ **JSON schema 자동 주입이 토큰 비용의 약 40%**. Spring AI Structured Output의 숨겨진 비용. 단계 2의 BeanOutputConverter 통찰과도 연결.

## raw 입력 데이터

- `prompt-1x.json`: 1x BaedalPrompt를 PromptLab payload로 보낸 입력
- `prompt-2x.json`: 2x BaedalPrompt (1x를 두 번 이어 붙임) 입력
- `step4-sync-response.json`: 시나리오 1 동기 호출 응답
