# 3단계 — 정상 순서 memory(10) → rag(20) 2턴 대화

## 1턴: 주문번호 2024-1234 배달 어디?
```
현재 배달 상태는 배송 중이며, 라이더의 위치는 역삼역 사거리 부근입니다. 예상 도착 시간은 2026년 6월 7일 오전 12시 28분입니다. 혹시 다른 궁금한 점이 있으신가요?
```

## 2턴: 아까 그 주문 환불 돼요?
```
해당 주문번호를 찾지 못했습니다. 다른 궁금한 점이 있으시면 알려주세요.
```

## 세션 메모리 (chain-normal)
```json
[{"type":"user","text":"주문번호 2024-1234 배달 어디?"},{"type":"assistant","text":"현재 배달 상태는 배송 중이며, 라이더의 위치는 역삼역 사거리 부근입니다. 예상 도착 시간은 2026년 6월 7일 오전 12시 28분입니다. 혹시 다른 궁금한 점이 있으신가요?"},{"type":"user","text":"아까 그 주문 환불 돼요?"},{"type":"assistant","text":"해당 주문번호를 찾지 못했습니다. 다른 궁금한 점이 있으시면 알려주세요."}]
```

## promptTokens
```
2026-06-07T00:13:44.072+09:00  INFO 65307 --- [baedal-support-agent] [nio-8080-exec-1] c.b.support.PerformanceLoggingAdvisor    : LLM call elapsedMs=12647 promptTokens=3587 completionTokens=102 totalTokens=3689
```
