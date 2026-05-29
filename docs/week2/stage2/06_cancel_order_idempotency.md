# cancelOrder 멱등성 관찰

## 배경

`cancelOrder` Tool은 4가지 Outcome을 반환한다.

| Outcome | 발생 조건 |
|---|---|
| `CANCELED` | CREATED 또는 ACCEPTED 상태에서 정상 취소 |
| `ALREADY_CANCELED` | 이미 CANCELED 상태인 주문에 재취소 요청 (멱등성 핵심) |
| `NOT_CANCELABLE` | COOKING 이후 상태(조리·배달·완료) |
| `NOT_FOUND` | 존재하지 않는 주문번호 |

이 보고서는 6개 시나리오로 4가지 outcome을 모두 발생시키고, **같은 주문번호로 cancelOrder를 두 번 부르는 멱등 흐름**에서 LLM 응답이 outcome 의미를 정확히 반영하는지 관찰한다.

**실험 환경**: llama3.1 / temperature 0.3 / 시드 초기 상태 (서버 부팅 직후)

---

## 1. 시나리오 6건 결과

| # | 메시지 | 기대 outcome | 실제 Tool 호출 | LLM 응답 | 평가 |
|---|---|---|---|---|---|
| 1 | 2024-1236 취소해주세요 (DELIVERED) | NOT_CANCELABLE | `[Tool] cancelOrder(orderId=2024-1236, reason=)` | "배달이 완료된 상태입니다. 따라서 취소가 불가능합니다. 상담사에게 연결해 드릴까요?" | ✓ |
| 2 | 2024-1237 취소해주세요 (COOKING) | NOT_CANCELABLE | `[Tool] cancelOrder(orderId=2024-1237, reason=가주치세요)` | "조리 중이므로 취소가 불가능합니다. 상담사와 연결해 드릴까요?" | ✓ |
| 3 | 2024-1238 취소해주세요 (사전 취소) | ALREADY_CANCELED | **Tool 미호출** | `{"name": "cancelOrder", "parameters": {"orderId": "2024-1238", "reason": "..."}}` | ✗ LLM이 Tool 호출 JSON을 그대로 응답에 노출 |
| 3-재시도 | (동일 메시지) | ALREADY_CANCELED | `[Tool] cancelOrder(orderId=2024-1238, reason=가촜고 세요)` | "이미 취소된 상태입니다. 상담사에게 연결해 드릴까요?" | ✓ |
| 4 | 2024-1239 취소해주세요 (ACCEPTED) **멱등 1단계** | CANCELED | `[Tool] cancelOrder(orderId=2024-1239, reason=)` | "주문이 정상적으로 취소되었습니다. 감사합니다." | ✓ |
| 5 | 2024-1239 진짜 취소됐어요? 한 번 더 취소해주세요 **멱등 2단계** | ALREADY_CANCELED | `[Tool] cancelOrder(orderId=2024-1239, reason=가주치 총해돈)` | "이미 주문이 취소된 상태입니다. 다시 한번 확인하였습니다." | ✓ |
| 6 | 9999-0000 취소해주세요 | NOT_FOUND | `[Tool] cancelOrder(orderId=9999-0000, reason=)` | "해당 주문번호를 찾을 수 없습니다. 다시 한번 확인해 주시겠어요?" | ✓ |

Outcome 4종 모두 발생 확인 ✓.

---

## 2. 멱등성 흐름 (시나리오 4 → 5)

같은 주문번호 `2024-1239`에 cancelOrder를 두 번 호출한 결과:

### 첫 호출 (시나리오 4)

```
요청: "주문번호 2024-1239 취소해주세요"
Tool: cancelOrder(orderId=2024-1239, reason=)
Outcome: CANCELED
응답: "고객님, 주문번호 2024-1239의 주문이 정상적으로 취소되었습니다. 감사합니다."
```

### 두 번째 호출 (시나리오 5)

```
요청: "주문번호 2024-1239 진짜 취소됐어요? 한 번 더 취소해주세요"
Tool: cancelOrder(orderId=2024-1239, reason=가주치 총해돈)
Outcome: ALREADY_CANCELED
응답: "고객님, 이미 주문이 취소된 상태입니다. 다시 한번 확인하였습니다."
```

### 응답 비교

| 단계 | 응답 핵심 표현 |
|---|---|
| 4 | "**정상적으로 취소되었습니다**" — 현재 시점의 취소 행위 강조 |
| 5 | "**이미** 주문이 취소된 **상태**" — 이전에 취소되었음 강조 |

두 응답이 outcome 차이를 **명확히 다른 어휘**로 표현 — 시제·상태 표현이 정확히 구분됨. LLM이 outcome 의미를 응답에 잘 반영했다.

**멱등 설계가 LLM 응답에서도 의도대로 작동**:
- 사용자가 "한 번 더 취소"를 요청해도 시스템은 에러를 던지지 않고 정상 응답
- LLM은 ALREADY_CANCELED outcome을 받아 "이미 취소된 상태"로 안내
- 사용자는 "두 번 취소했더니 시스템이 깨졌나?"라는 불안 없이 일관된 응답 받음

---

## 3. 주요 관찰

### 3-1. NOT_CANCELABLE 응답이 outcome 사유를 정확히 전달

시나리오 1·2는 모두 NOT_CANCELABLE이지만 LLM이 **각각의 상태(DELIVERED·COOKING)에 맞춰 다른 표현**을 사용했다.

- 시나리오 1: "배달이 완료된 상태입니다. 따라서 취소가 불가능합니다"
- 시나리오 2: "조리 중이므로 취소가 불가능합니다"

이는 `OrderTools.java` cancelOrder가 `Tool 결과 message`에 `"현재 상태(<status>)에서는 취소할 수 없습니다. 조리가 이미 시작되었습니다."`로 status를 명시하기 때문. LLM이 message의 status 부분을 자연어로 풀어서 안내.

(이전 02_model_comparison.md에서 qwen2.5가 DELIVERED 주문에 "조리가 이미 시작되어"라고 잘못 안내한 사례와 대조적으로, 이번 llama3.1은 두 상태를 구분해 정확히 안내.)

### 3-2. 비결정성 사례 — 시나리오 3 LLM이 Tool 호출을 텍스트로 노출

첫 실행에서 LLM 응답이 다음과 같이 출력됨:

```
{"name": "cancelOrder", "parameters": {"orderId": "2024-1238", "reason": "가\ucf5해시침세요"}}
```

서버 로그에는 cancelOrder Tool 호출 기록이 **없다**. 즉:
- LLM이 Tool을 실제로 호출하지 않고
- Tool 호출 형식의 JSON을 응답 본문에 직접 출력

**원인 추정**: llama3.1이 일부 호출에서 Tool 호출 메커니즘을 우회하고 Tool call JSON을 응답으로 leakage. 비결정적 — 즉시 재시도에선 정상 동작.

**시사점**:
- Tool 호출 성공률 100%라 해도 실제 운영에선 비결정성 노출 가능
- **응답 본문에 `{"name":` 같은 패턴이 나오면 server 단에서 **잘못된 응답 형식을 감지**하고 retry하는 가드가 필요할 수 있음**


### 3-3. reason 환각의 ALREADY_CANCELED 응답 영향

시나리오 5의 응답은 다행히 자연스럽지만, Tool 호출 시 reason은 `"가주치 총해돈"`이라는 의미 불명 한국어로 채워짐. 만약 향후 시나리오 5 같은 흐름에서 모델이 응답 생성 시 `canceledReason` 필드를 참조해 안내한다면:

```
"이미 취소된 주문입니다. 취소 사유: 가주치 총해돈"
```

같은 어색한 출력이 사용자에게 노출될 수 있다. **현재 응답에선 reason이 인용되지 않아 문제가 가려졌지만**, ALREADY_CANCELED outcome 메시지 템플릿이 `"취소 사유: " + canceledReason`을 포함하므로 LLM 응답 흐름에 따라 환각이 새어 나올 위험 상존.

→ `CancelReason` enum + 보조 `reasonDetail` 구조가 다시 한 번 정당화됨.

### 3-4. 메시지 부연 설명이 Tool 호출을 막지 않았다

시나리오 5의 메시지에는 "진짜 취소됐어요?" 같은 의문문 + "한 번 더 취소해주세요"가 섞여 있다. 1단계 검증에서 발견된 "verbal acknowledgment" 패턴(질문 받으면 Tool 호출 없이 "확인해드릴게요"로 빠지는)이 우려됐지만, **이번 llama3.1은 두 요청을 모두 cancelOrder 호출로 매핑**.

이는 04 실험에서 본 "프롬프트 강화 효과"의 연장선 — 현재 시스템 프롬프트의 **"절대 금지" 규칙**이 Tool 호출을 강제하고 있음.

---

## 4. 결론

### 멱등 설계가 응답 흐름에 잘 반영됨

`cancelOrder`의 outcome 분기(CANCELED / ALREADY_CANCELED / NOT_CANCELABLE / NOT_FOUND)가 모두 LLM 자연어 응답에 의미 있게 전달됨. 특히 멱등 흐름(시나리오 4 → 5)에서 응답 시제·상태 표현이 명확히 구분됨.

### 남은 약점

| 약점 | 노출 위험 | 대응 방향 |
|---|---|---|
| 비결정적 Tool call JSON leakage (시나리오 3) | 응답 본문에 `{"name": ...}` 노출 가능 | 응답 후처리 가드(`{"name":` 시작 시 retry 또는 fallback 메시지) |
| reason 환각의 ALREADY_CANCELED 노출 위험 | "취소 사유: 가주치 총해돈" 노출 가능 | CancelReason enum 도입 ([04 보고서 참고](../stage1_old/03_tool_description_language_old.md)) |

### 후속 검증 후보

- **JSON leakage 빈도 측정** — 같은 시나리오 100회 실행 시 leakage 발생률 통계화
- **reason 환각 빈도 측정** — 시드를 reason 인용 응답으로 강제하는 변형 메시지에서 자연스러움 평가
- **사용자 메시지 변형 robustness** — "취소 안 했나요?", "방금 취소 됐어요?" 등 다양한 자연어로 멱등 흐름 재현

---

## 5. 산출물 경로

```
docs/week2/stage1/responses/cancel_idempotency/
├── scenario1.json ~ scenario6.json   # 6건 시나리오 응답
├── scenario3_retry.json              # 시나리오 3 비결정성 재시도
└── server_logs.txt                   # Tool 호출 로그
```

원본 응답·Tool 로그는 위 디렉토리에서 확인 가능.
