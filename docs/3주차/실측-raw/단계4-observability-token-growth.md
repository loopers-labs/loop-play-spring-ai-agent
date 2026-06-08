# 단계 4 Observability raw

실행 기준:

- 실행 시각: 2026-06-01 KST
- 모델: Ollama `qwen2.5`
- 엔드포인트: `/api/v1/assistant`
- 저장소: 기본 profile, `InMemoryChatMemoryRepository`
- Memory policy: `baedal.chat-memory.max-messages=20`
- raw 원본: `docs/3주차/실측-raw/단계2-memory-window-10턴.md`의 `MAX_MESSAGES = 20`

단계 4에서는 같은 10턴 실행을 다시 결론 재료로 썼다. 이유는 단계 2에서 이미 같은 조건의 advisor 로그와 최종 memory JSON을 남겼고, 단계 4의 질문이 "Memory window 크기 비교"가 아니라 "Memory가 붙었을 때 관측 신호를 어디까지 믿을 수 있는가"였기 때문이다.

## advisor 로그

```text
promptTokens:     2982, 1532, 3340, 1671, 1766, 1860, 1934, 1998, 2090, 2162
completionTokens:   90,   27,  120,   71,   72,   59,   41,   73,   47,   85
elapsedMs:        7011, 4751, 3440, 1975, 1839, 1525, 1115, 2012, 1266, 2180
average: prompt=2133.5, completion=68.5, elapsedMs=2711.4
```

## 턴별 표

| 턴 | 입력 토큰 | 출력 토큰 | 응답 시간(ms) | 관찰 |
| ---: | ---: | ---: | ---: | --- |
| 1 | 2982 | 90 | 7011 | `2024-1234` 배달 상황. Tool 호출로 보이는 응답 |
| 2 | 1532 | 27 | 4751 | "그거 몇 분"을 이전 assistant 답변에서 이어 말함 |
| 3 | 3340 | 120 | 3440 | `2024-1235` 메뉴 조회 |
| 4 | 1671 | 71 | 1975 | "버거 세트"라고 잘못 바뀜. Tool 없이 Memory 추론으로 보임 |
| 5 | 1766 | 72 | 1839 | `2024-1234` 취소 가능 여부 |
| 6 | 1860 | 59 | 1525 | `1235` 취소 가능 여부 |
| 7 | 1934 | 41 | 1115 | "그거 취소"를 `2024-1235`로 해석 |
| 8 | 1998 | 73 | 2012 | `2024-1234` 도착 시간 재확인 |
| 9 | 2090 | 47 | 1266 | "그 주문"을 `2024-1234`로 해석 |
| 10 | 2162 | 85 | 2180 | 지금까지 질문 요약 |

## 같이 봐야 하는 단계 1 smoke

단계 1 smoke의 2턴은 응답만 보면 Memory가 잘 된 것처럼 보였다.

```text
LLM call elapsedMs=5612 promptTokens=1533 completionTokens=62 totalTokens=1595
```

하지만 2턴에는 다음 Tool 로그가 새로 찍히지 않았다.

```text
Executing tool call: getDeliveryStatus
[Tool] getDeliveryStatus(orderId=2024-***)
```

그래서 이번 단계의 판단은 "지시 대명사가 해결됐다"가 아니라 "이 입력에서는 assistant 자연어 답변에 남은 도착 시간을 재사용했다"에 가깝다.

## 캡처하지 못한 것

Memory advisor가 실제로 모델에게 넘긴 프롬프트 전문은 이번 raw에 없다. 확인한 것은 다음 세 가지다.

- 세션 메시지 API에 USER/ASSISTANT 대화가 순서대로 남았다.
- 다음 턴 advisor 로그의 prompt token이 대화 길이에 따라 달라졌다.
- Tool 로그가 없는 턴에서도 이전 assistant 응답을 재사용하는 사례가 있었다.

따라서 이 raw만으로는 "프롬프트에 정확히 어떤 문자열이 삽입됐다"까지는 말할 수 없다.
