# 2단계: 멱등성 관찰 — cancelOrder를 두 번 부르면


## 검증 — Outcome 4종

| # | 시나리오 | 기대 Outcome | 대상 주문 |
|---|---|---|---|
| A | 배달 완료된 주문 취소 | `NOT_CANCELABLE` | `2024-1236` (DELIVERED) |
| B-1 | 취소 가능 주문 첫 취소 | `CANCELED` | `2024-1239` (ACCEPTED) |
| B-2 | 방금 취소한 주문 재취소 | `ALREADY_CANCELED` | `2024-1239` (B-1 이후) |
| C | 존재하지 않는 주문번호 취소 | `NOT_FOUND` | `9999-0000` |

```bash
# A: NOT_CANCELABLE (배달 완료 상태)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1236 취소해주세요"}'

# B-1: CANCELED (첫 취소)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1239 취소해주세요"}'

# B-2: ALREADY_CANCELED (재취소 — B-1 직후 실행)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1239 진짜 취소됐어요? 한 번 더 취소해주세요"}'

# C: NOT_FOUND
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 9999-0000 취소해주세요"}'
```

---

### Outcome: NOT_CANCELABLE

**응답 본문**

```
죄송합니다, 주문번호 2024-1236의 경우 이미 배달이 완료된 상태로 조리가 시작되어 취소할 수 없습니다.
다른 요청이나 궁금한 점이 있으시다면 알려주세요.
```

**콘솔 Tool 로그**

```
[Tool] cancelOrder(orderId=2024-1236, reason=고객 요청)
LLM 호출 완료 — 94193ms | 입력 토큰: 1159 | 출력 토큰: 93 | 총 토큰: 1252
```

---

### Outcome: CANCELED

**응답 본문**

```
주문번호 2024-1239는 성공적으로 취소되었습니다.
```

**콘솔 Tool 로그**

```
[Tool] cancelOrder(orderId=2024-1239, reason=고객 요청)
LLM 호출 완료 — 22988ms | 입력 토큰: 1151 | 출력 토큰: 58 | 총 토큰: 1209
```

---

### Outcome: ALREADY_CANCELED

> ⚠️ B-2 메시지("진짜 취소됐어요?")에 확인 질문이 섞여 있어, 모델이 `cancelOrder` 대신 `getOrderDetail`을 호출했다.
> `cancelOrder`의 `ALREADY_CANCELED` 코드 경로는 이 테스트에서 실행되지 않았다.

**응답 본문**

```
이미 주문번호 2024-1239는 취소된 상태입니다. 확인해 보니 BBQ 잠실점에서 주문하신
황금올리브치킨(1개)은 2026년 5월 23일 19시 26분에 취소되었습니다.

다시 한번 취소하시려면 다른 이유를 말씀해주셔야 합니다. 혹시 다른 문제가 있으신가요?
```

**콘솔 Tool 로그**

```
[Tool] getOrderDetail(orderId=2024-1239)   ← cancelOrder가 아님
LLM 호출 완료 — 53816ms | 입력 토큰: 1249 | 출력 토큰: 142 | 총 토큰: 1391
```

---

### Outcome: NOT_FOUND

**응답 본문**

```
존재하지 않는 주문번호입니다. 다시 확인해주세요.
```

**콘솔 Tool 로그**

```
[Tool] cancelOrder(orderId=9999-0000, reason=고객 요청)
LLM 호출 완료 — 22529ms | 입력 토큰: 1150 | 출력 토큰: 51 | 총 토큰: 1201
```

---

## 실패 관찰 — 멱등성 분기 제거 실험

> ⚠️ 형식적인 "안 됐어요"가 아니라, 시스템이 어떻게 망가지는지 출력 자체를 그대로 기록하는 것이 핵심.

**준비**: 아래 분기를 주석 처리 후 서버 재시작. `2024-1239`를 연속 2회 취소 요청.

```java
if (order.status() == OrderStatus.CANCELED) {
    return new CancelOrderResult(orderId, CancelOrderResult.Outcome.ALREADY_CANCELED,
            "이미 취소된 주문입니다. 취소 사유: " + order.canceledReason());
}
```

```bash
# D-1: 첫 취소 (CANCELED 기대)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1239 취소해주세요"}'

# D-2: 재취소 — 멱등성 분기 없이 (무슨 일이 일어나는가?)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1239 한 번 더 취소해주세요"}'
```

### D-1 응답 (첫 취소)

```
주문번호 2024-1239는 성공적으로 취소되었습니다.
```

### D-2 응답 (재취소 — 멱등성 분기 없음)

```
orderid = "2024-1239"
result = cancelOrder(orderid, "다시 한번 고객님의 요청에 따라 주문이 취소되었습니다.")
result
```

자연어 응답이 아닌 pseudocode가 그대로 출력됐다.

**콘솔 Tool 로그 (D-1, D-2 합쳐서)**

```
[D-1] cancelOrder(orderId=2024-1239, reason=고객 요청) — 실행됨
      LLM 호출 완료 — 102394ms | 입력 토큰: 1151 | 출력 토큰: 58 | 총 토큰: 1209

[D-2] Tool 실행 로그 없음
      LLM 호출 완료 — 9092ms  | 입력 토큰: 538  | 출력 토큰: 39 | 총 토큰: 577
```

D-2에서 `cancelOrder`는 실행되지 않았다. 입력 토큰 538은 D-1(1151)의 절반 이하다. tool call 파싱이 실패하면 Spring AI가 second-turn LLM call을 도구 정의 없이 보내는 경우가 있는데, 그 흐름으로 빠진 것으로 보인다. `canceledReason`은 덮어쓰이지 않았다 — `cancel()`이 아예 실행되지 않았으니.

---

### 고객에게 줄 수 있는 오해 3가지

1. pseudocode 응답에 "주문이 취소되었습니다"가 포함되어 있어, 고객이 취소가 한 번 더 성공했다고 읽을 수 있다.
2. NOT_CANCELABLE이 반환됐다면 "조리가 시작되어 취소할 수 없습니다. 현재 상태: CANCELED" => 이미 취소된 주문인데 왜 안 된다는 건지 이유를 알 수 없다.
3. pseudocode나 오류 응답을 받은 고객은 취소가 됐는지 안 됐는지 알 수 없어 상담사에게 다시 연락한다.

---

### 멱등성 분기가 없었다면 프로덕션에서 생겼을 장애 3가지

1. 취소 확인 이메일/알림이 가게와 고객에게 두 번 발송된다.
2. `isCancelable()` 구현이 달랐다면 `cancel()`이 두 번 호출되어 `canceledReason`이 두 번째 요청의 사유로 덮어쓰인다 => 환불 근거 데이터가 오염된다.
3. 환불 API가 두 번 호출되어 이중 환급이 발생한다.

---

## 설계 결정 문서

### Outcome이 4개인 이유 (`UNKNOWN` / `FAILED`를 넣지 않은 이유)

비즈니스에서 발생하는 실패 상황은 이미 다 알고 있다. 주문이 없거나(`NOT_FOUND`), 이미 취소됐거나(`ALREADY_CANCELED`), 취소 불가 상태거나(`NOT_CANCELABLE`). 이 세 가지 외에 "무슨 이유인지 모르겠는 실패"를 만들 이유가 없다.

`UNKNOWN`이나 `FAILED`를 넣으면 LLM이 고객에게 줄 수 있는 말이 "뭔가 문제가 생겼어요" 하나다. 고객 입장에서는 재시도해야 하는지, 상담사를 연결해야 하는지, 그냥 기다려야 하는지 알 수 없다. Outcome을 구체적으로 쪼갤수록 LLM이 상황에 맞는 다음 행동을 안내할 수 있다.

진짜 예상치 못한 오류 (DB 연결 실패, NullPointerException 같은 것들) 은 결과 값으로 처리하는 게 아니라 예외로 터뜨리는 게 맞다. 비즈니스 실패와 시스템 오류는 다르다. 이 Tool에서 결과 값으로 처리하는 건 전부 "이런 상황이 있을 수 있다"고 미리 알고 있는 것들이다.

---

### 배달 운영에서 실제로 추가될 법한 Outcome

| Outcome | 시나리오 |
|---|---|
| `PARTIAL_REFUND` | 주문 일부만 배달된 경우 — 취소는 되지만 전액 환불이 아니라 계산이 필요 |
| `REQUIRES_AGENT` | 가게 측에서 이미 재료를 쓴 경우 등 분쟁이 있어 자동 처리 불가, 상담사 연결 필요 |
| `COOLING_OFF` | 소비자 보호법 철회권 적용 케이스 — 취소 가능하나 일반 취소와 다른 환불 규정 적용 |

---

### 멱등성 3수준 중 "같은 응답 재전달"을 택한 이유

멱등성 구현에는 대략 세 가지 선택지가 있다.

| 수준 | 동작 |
|---|---|
| 1 | 같은 요청이면 아무것도 안 하고 같은 응답 반환 |
| 2 | 중복 요청임을 감지해서 에러 반환 |
| 3 | 매 요청마다 새로 실행 (멱등성 없음) |

cancelOrder에 "같은 응답 재전달"(수준 1)을 쓴 이유는, 고객의 최종 의도가 이미 이뤄져 있기 때문이다. 고객은 "취소돼야 한다"는 결과를 원하는 거고, 그게 이미 됐다면 에러를 줄 이유가 없다. 재시도가 안전하고, LLM도 에러 처리 없이 "이미 취소됐다"를 그대로 전달할 수 있다.

에러가 더 적절한 경우는 "중복이면 심각한 문제"인 상황이다. 결제 처리가 대표적이다. 같은 결제 요청이 두 번 오면 에러로 막아야 한다. 재전달하면 이중 결제가 된다. 인증 토큰 재사용, 계좌 이체도 마찬가지다. "이미 됐으니 괜찮아요"가 아니라 "왜 또 왔어요?"가 맞는 경우다.

---

## 회고

---

### 회고 1 — B-2: 예상한 Tool이 아닌 게 불렸다

"주문번호 2024-1239 진짜 취소됐어요? 한 번 더 취소해주세요"를 보냈더니 `cancelOrder` 대신 `getOrderDetail`이 실행됐다.

```
[Tool] getOrderDetail(orderId=2024-1239)
```

"진짜 취소됐어요?" 라는 확인 질문이 섞여 있으니 모델이 "조회 요청"으로 읽었을 것이다. 취소 요청과 확인 질문을 한 메시지에 넣으면 모델이 어느 쪽에 더 반응할지 알 수 없다. `cancelOrder`의 `ALREADY_CANCELED` 경로를 직접 확인하려면 2024-1238(사전 취소됨)을 쓰거나 메시지에서 확인 질문을 빼야 한다.

---

### 회고 2 — Tool이 안 준 정보를 LLM이 채워넣었다

B-2 응답에 이 문장이 들어 있었다.

```
2026년 5월 23일 19시 26분에 취소되었습니다.
```

`OrderDetailView`에 `canceledAt`은 없다. 1단계에서 의도적으로 뺀 필드다. 그런데 LLM이 없는 타임스탬프를 응답에 넣었다. 실제 취소 시각은 19:37이다. 숫자도 다르다.

같은 응답에 없는 규칙도 하나 더 나왔다. "다시 한번 취소하시려면 다른 이유를 말씀해주셔야 합니다." 그런 규칙은 어디에도 없다. Tool이 안 준 정보는 LLM이 만든다. 잘못된 시각과 없는 규칙, 두 가지가 동시에 나왔다.

---

### 회고 3 — 설계 결정은 그게 다루지 않는 상황에서 드러난다

1단계에서 `OrderDetailView`에 `canceledAt`을 뺀 이유는 "취소 전 주문에서는 null이라 불필요하다"였다. 그 결정 자체는 맞다. 그런데 이미 취소된 주문을 `getOrderDetail`로 조회하면 `canceledAt`이 없어서 LLM이 타임스탬프를 만든다.

설계 결정은 "그 결정이 상정한 상황" 안에서는 맞다. 그 밖의 상황에서 어떻게 될지는 직접 실험해봐야 안다. 이번 B-2가 그 예다.

---

### 회고 4 — D-2: Tool이 실행되지 않고 pseudocode가 나왔다

멱등성 분기를 제거하고 같은 주문을 두 번 취소 요청했을 때, 두 번째 응답이 이렇게 나왔다.

```
orderid = "2024-1239"
result = cancelOrder(orderid, "다시 한번 고객님의 요청에 따라 주문이 취소되었습니다.")
result
```

로그에 `[Tool] cancelOrder`가 없었다. 실행 자체가 안 됐다. 토큰도 달랐다.

```
D-1: 입력 1151 | 출력 58 | 총 1209
D-2: 입력  538 | 출력 39 | 총  577
```

D-2 입력 토큰이 538이다. 도구 정의가 포함됐다면 1000 토큰은 넘어야 한다. tool call 파싱이 실패하면 Spring AI가 second-turn call을 도구 정의 없이 보내는 경우가 있는데, 그 흐름으로 빠진 것으로 보인다. 1단계에서 `cancelOrder` description이 길었을 때 `portun` 텍스트가 나왔던 패턴과 비슷하다.

ALREADY_CANCELED 분기가 없으면 모델이 두 번째 취소 요청에 어떻게 반응해야 할지 context가 없다. 그 상태에서 "한 번 더 취소"를 요청하니 tool call을 제대로 생성하지 못하고 pseudocode를 뱉은 것으로 보인다. 멱등성 분기는 단순히 중복 처리를 막는 게 아니라, LLM에게 "이 상황에서는 이렇게 행동하라"는 context를 주는 역할도 한다.
