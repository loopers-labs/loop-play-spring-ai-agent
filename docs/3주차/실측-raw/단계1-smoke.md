# 단계 1 smoke

실행 시각: 2026-06-01 17:41 KST

조건:

- `./gradlew bootRun`
- Ollama `localhost:11434` 응답 가능
- 기본 profile, InMemory `ChatMemoryRepository`
- `X-Session-Id: smoke-r3`

## 1턴

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: smoke-r3" \
  -d '{"message":"2024-1234 어디쯤 있어요?"}'
```

응답:

```text
현재 배달원은 역삼역 사거리 부근에 있으며, 예상 도착 시간은 2026년 6월 1일 오후 5시 56분 39초입니다. 추가로 궁금한 점이 있으신가요?
```

관찰 로그:

```text
Executing tool call: getDeliveryStatus
[Tool] getDeliveryStatus(orderId=2024-***)
LLM call elapsedMs=9856 promptTokens=2980 completionTokens=93 totalTokens=3073
```

## 2턴

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: smoke-r3" \
  -d '{"message":"그거 언제 도착해요?"}'
```

응답:

```text
예상 도착 시간은 2026년 6월 1일 오후 5시 56분 39초입니다. 안전한 배달을 위해 조금 더 기다려 주세요. 다른 궁금한 점이 있으신가요?
```

관찰 로그:

```text
LLM call elapsedMs=5612 promptTokens=1533 completionTokens=62 totalTokens=1595
```

2턴에서는 Tool 호출 로그가 새로 남지 않았다. 이번 입력에서는 LLM이 1턴 assistant 응답에 들어 있던 도착 시간을 재사용한 것으로 보인다. 이것은 Memory 연결이 되었음을 보여주는 약한 증거이지만, `getDeliveryStatus(2024-1234)` 재호출까지 보장한 것은 아니다. 본 실험에서는 각 턴의 Tool 호출 여부를 함께 봐야 한다.

## 세션 메시지

```bash
curl -s http://localhost:8080/api/v1/session/smoke-r3/messages
```

```json
[
  {
    "type": "user",
    "text": "2024-1234 어디쯤 있어요?"
  },
  {
    "type": "assistant",
    "text": "현재 배달원은 역삼역 사거리 부근에 있으며, 예상 도착 시간은 2026년 6월 1일 오후 5시 56분 39초입니다. 추가로 궁금한 점이 있으신가요?"
  },
  {
    "type": "user",
    "text": "그거 언제 도착해요?"
  },
  {
    "type": "assistant",
    "text": "예상 도착 시간은 2026년 6월 1일 오후 5시 56분 39초입니다. 안전한 배달을 위해 조금 더 기다려 주세요. 다른 궁금한 점이 있으신가요?"
  }
]
```
