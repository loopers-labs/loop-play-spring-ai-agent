# 1단계: ChatMemory 3레이어 + X-Session-Id + 지시 대명사 시나리오

## 검증 — 시나리오 5종

### 시나리오 1 — 지시 대명사 "그거" (같은 세션)

> **기대와 다른 결과**: 과제 기대는 2회차에서 `getDeliveryStatus(2024-1234)` 재호출이었지만, 실제로는 Tool을 호출하지 않고 Memory에서 답을 꺼냈다. 원인 분석은 회고 1, 4 참조.

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: smoke-test" \
  -d '{"message":"2024-1234 어디쯤이에요?"}'

curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: smoke-test" \
  -d '{"message":"그거 몇 시에 도착해요?"}'
```

**1회차 응답**

```
현재 라이더는 역삼역 사거리 부근에서 배달 중입니다. 예상 도착 시간은 2026년 5월 30일 오후 6시 1분입니다.
```

**2회차 응답**

```
예상 도착 시간은 2026년 5월 30일 오후 6시 1분입니다. 정확한 도착 시간은 실시간 교통 상황에 따라 변동될 수 있습니다.
```

**Memory 상태 (`/api/v1/session/smoke-test/messages`)**

```json
[
  {
    "type": "USER",
    "content": "2024-1234 어디쯤이에요?"
  },
  {
    "type": "ASSISTANT",
    "content": "현재 라이더는 역삼역 사거리 부근에서 배달 중입니다. 예상 도착 시간은 2026년 5월 30일 오후 6시 1분입니다."
  },
  {
    "type": "USER",
    "content": "그거 몇 시에 도착해요?"
  },
  {
    "type": "ASSISTANT",
    "content": "예상 도착 시간은 2026년 5월 30일 오후 6시 1분입니다. 정확한 도착 시간은 실시간 교통 상황에 따라 변동될 수 있습니다."
  }
]
```

**콘솔 로그**

```
[Assistant] sessionId=smoke-test, message=2024-1234 어디쯤이에요?
[Tool] getDeliveryStatus(orderId=2024-1234)
LLM 호출 완료 — 9849ms | 입력 토큰: 1273 | 출력 토큰: 100 | 총 토큰: 1373

[Assistant] sessionId=smoke-test, message=그거 몇 시에 도착해요?
LLM 호출 완료 — 5191ms | 입력 토큰: 663 | 출력 토큰: 51 | 총 토큰: 714
```

> 1회차에서 `getDeliveryStatus(2024-1234)`가 실행됐다. "역삼역 사거리" 포함.
> 2회차 "그거"는 Tool 재호출 없이 Memory의 1회차 응답에서 도착 시간을 꺼내 답변했다.
> 과제 기대(2회차 Tool 재호출)와 다른 결과인데, 모델의 자율 판단이 아니다. `ASSISTANT_PROMPT`에
> "이미 조회한 정보는 재사용하세요"라는 지시가 있어서 생략한 것이다. (회고 1, 4 참조)
>
> 입력 토큰이 1273 → 663으로 오히려 줄었다. 1회차는 Tool Calling의 2차 LLM 호출(Tool 결과 JSON 포함)이
> PerformanceLoggingAdvisor에 찍힌 것이고, 2회차는 Tool 없이 Memory(짧은 텍스트 2개) + 질문만
> 들어간 1회 호출이라 더 작다. Tool 결과 JSON이 Memory 추가분보다 토큰을 더 많이 먹는다.
>
> 배달 위치는 매 분 바뀌는 실시간 데이터다. Memory에서 꺼낸 "오후 6시 1분"은 1회차 시점 기준이지
> 지금 시각이 아니다. 재사용 지시에 "실시간 데이터는 예외"라는 조항이 없어서 생긴 문제다.

---

### 시나리오 2 — 취소 대상 전환 ("아, 그거 말고")

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A2" \
  -d '{"message":"2024-1234 취소해주세요"}'

curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A2" \
  -d '{"message":"아 그거 말고 2024-1235 취소해주세요"}'
```

**1회차 응답**

```
죄송합니다, 해당 주문은 이미 조리 과정에 들어갔기 때문에 취소할 수 없습니다. 현재 배달 상태는 진행 중입니다. 다른 도움이 필요하시면 알려주세요!
```

**2회차 응답**

```
해당 주문이 성공적으로 취소되었습니다. 다른 도움이 필요하시면 언제든지 알려주세요!
```

**Memory 상태**

```json
[
  {
    "type": "USER",
    "content": "2024-1234 취소해주세요"
  },
  {
    "type": "ASSISTANT",
    "content": "죄송합니다, 해당 주문은 이미 조리 과정에 들어갔기 때문에 취소할 수 없습니다. 현재 배달 상태는 진행 중입니다. 다른 도움이 필요하시면 알려주세요!"
  },
  {
    "type": "USER",
    "content": "아 그거 말고 2024-1235 취소해주세요"
  },
  {
    "type": "ASSISTANT",
    "content": "해당 주문이 성공적으로 취소되었습니다. 다른 도움이 필요하시면 언제든지 알려주세요!"
  }
]
```

**콘솔 로그**

```
[Assistant] sessionId=cust-A2, message=2024-1234 취소해주세요
[Tool] cancelOrder(orderId=2024-1234, reason=고객 요청)
LLM 호출 완료 — 8186ms | 입력 토큰: 1222 | 출력 토큰: 96 | 총 토큰: 1318

[Assistant] sessionId=cust-A2, message=아 그거 말고 2024-1235 취소해주세요
[Tool] cancelOrder(orderId=2024-1235, reason=고객 요청)
LLM 호출 완료 — 6011ms | 입력 토큰: 1409 | 출력 토큰: 61 | 총 토큰: 1470
```

> 1회차: `cancelOrder(2024-1234)` → NOT_CANCELABLE. 2024-1234는 DELIVERING 상태라 취소 불가.
> 2회차: "그거"가 Memory의 1234를 가리키고, "말고"로 새 대상 1235로 전환됐다. `cancelOrder(2024-1235)` 실행 → CANCELED.
> 입력 토큰 1222 → 1409(+187). Memory에 1회차 USER+ASSISTANT 2개 메시지가 추가된 만큼 늘었다.
>
> "그거 말고"라는 자연어 전환이 처리됐다. LLM이 "1234 취소 실패" 맥락을 읽고 "그거"=1234,
> 새 대상=1235라는 구조를 이해했다. Memory가 없었다면 그냥 "1235를 취소합니다"로 끝났을 텐데,
> Memory 덕분에 1234와의 관계까지 파악했다.

---

### 시나리오 3 — "아까 물어본 그 주문" (이전 턴 참조)

**요청**

```bash
# 먼저 1234 언급
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A3" \
  -d '{"message":"2024-1234 주문 상태 확인해줘"}'

# 이전 턴 참조
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A3" \
  -d '{"message":"아까 물어본 그 주문 언제 도착해요?"}'
```

**1회차 응답**

```
주문번호 2024-1234의 상태는 배달 중입니다. 주문한 음식은 허니콤보(1개, 가격: 23,000원)와 콜라 1.25L(1개, 가격: 3,000원)이며, 총 금액은 26,000원입니다. 예상 배달 시간은 2026년 5월 30일 오후 6시 24분입니다.
```

**2회차 응답**

```
아까 말씀하신 주문번호 2024-1234의 예상 배달 시간은 2026년 5월 30일 오후 6시 24분입니다. 정확한 도착 시간이 변경될 수 있으니, 최신 정보를 확인하려면 다시 한번 배송 상태를 확인해보세요.
```

**Memory 상태**

```json
[
  {
    "type": "USER",
    "content": "2024-1234 주문 상태 확인해줘"
  },
  {
    "type": "ASSISTANT",
    "content": "주문번호 2024-1234의 상태는 배달 중입니다. 주문한 음식은 허니콤보(1개, 가격: 23,000원)와 콜라 1.25L(1개, 가격: 3,000원)이며, 총 금액은 26,000원입니다. 예상 배달 시간은 2026년 5월 30일 오후 6시 24분입니다."
  },
  {
    "type": "USER",
    "content": "아까 물어본 그 주문 언제 도착해요?"
  },
  {
    "type": "ASSISTANT",
    "content": "아까 말씀하신 주문번호 2024-1234의 예상 배달 시간은 2026년 5월 30일 오후 6시 24분입니다. 정확한 도착 시간이 변경될 수 있으니, 최신 정보를 확인하려면 다시 한번 배송 상태를 확인해보세요."
  }
]
```

**콘솔 로그**

```
[Assistant] sessionId=cust-A3, message=2024-1234 주문 상태 확인해줘
[Tool] getOrderDetail(orderId=2024-1234)
LLM 호출 완료 — 10483ms | 입력 토큰: 1348 | 출력 토큰: 179 | 총 토큰: 1527

[Assistant] sessionId=cust-A3, message=아까 물어본 그 주문 언제 도착해요?
LLM 호출 완료 — 6716ms | 입력 토큰: 742 | 출력 토큰: 81 | 총 토큰: 823
```

> "아까 물어본 그 주문"에서 Memory의 2024-1234를 꺼내 답변. Tool은 재호출하지 않았다. (회고 1 참조)
> 1회차 ASSISTANT 응답에 이미 배달 시간이 있어서 그걸 그대로 꺼낸 것이다.
> 입력 토큰 1348 → 742(-606). 시나리오 1과 같다. Tool 결과 JSON이 빠지면 오히려 줄어든다.
>
> "그거"보다 더 모호한 "아까 물어본 그 주문"도 됐다. LLM이 Memory 전체를 읽고 "아까"가 직전 턴을
> 가리킨다는 걸 이해했다.

---

### 시나리오 4 — 세션 오염 테스트 (A ↔ B 격리)

**요청**

```bash
# 세션 A: 1234 언급
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A4" \
  -d '{"message":"2024-1234 배달 현황 알려줘"}'

# 세션 B: 맥락 없이 "그 주문" 질문
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-B4" \
  -d '{"message":"그 주문 어디쯤이야?"}'
```

**세션 A 응답**

```
주문번호 2024-1234의 배달 현황을 알려드리면, 현재 상태는 배달 중이며 라이der는 역삼역 사거리 부근에 있습니다. 예상 도착 시간은 2026년 5월 30일 오후 6시 1분입니다.
```

**세션 B 응답**

```
어떤 주문을 말씀하시는 건가요? 최근에 처리한 주문 번호를 알려주시면 도와드리겠습니다.
```

**세션 B의 Memory 상태 (`/api/v1/session/cust-B4/messages`)**

```json
[
  {
    "type": "USER",
    "content": "그 주문 어디쯤이야?"
  },
  {
    "type": "ASSISTANT",
    "content": "어떤 주문을 말씀하시는 건가요? 최근에 처리한 주문 번호를 알려주시면 도와드리겠습니다."
  }
]
```

**등록된 세션 목록 (`/api/v1/session/ids`)**

```json
["cust-A2", "cust-A4", "cust-A3", "cust-B4", "smoke-test"]
```

**콘솔 로그**

```
[Assistant] sessionId=cust-A4, message=2024-1234 배달 현황 알려줘
[Tool] getDeliveryStatus(orderId=2024-1234)
LLM 호출 완료 — 7290ms | 입력 토큰: 1279 | 출력 토큰: 104 | 총 토큰: 1383

[Assistant] sessionId=cust-B4, message=그 주문 어디쯤이야?
LLM 호출 완료 — 1283ms | 입력 토큰: 570 | 출력 토큰: 29 | 총 토큰: 599
```

> 세션 B는 A의 대화를 전혀 모른다. B의 Memory에는 B의 질문과 응답만 있고, cust-A4의 1234 언급은 없다.
> 세션 목록에서 cust-A4와 cust-B4가 별도로 등록됐다.
> 세션 B 입력 토큰은 570. Tool도, 이전 Memory도 없는 첫 질문이라 기준 토큰에 가깝다.
>
> 이 격리가 동작하는 건 `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))` 한 줄 때문이다.
> 이 줄이 빠지면 컴파일 에러도 런타임 에러도 없다. 앱이 정상 동작하는 것처럼 보이면서
> 모든 고객의 대화가 같은 Memory에 쌓인다. 프로덕션에서 터지면 찾기 어렵다.
>
> 세션 ID를 아는 사람은 누구든 그 대화 이력에 접근할 수 있다는 점도 기억해 둘 만 하다.
> 실제 서비스에서 세션 식별은 인증과 함께 설계되어야 할 것이다.

---

#### 추가 검증: default 세션 오염


`X-Session-Id` 헤더 없이 요청

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"2024-1234 어디쯤이에요?"}'

curl -s http://localhost:8080/api/v1/session/default/messages | jq
```

**default 세션 상태**

```json
[
  {
    "type": "USER",
    "content": "2024-1234 어디쯤이에요?"
  },
  {
    "type": "ASSISTANT",
    "content": "현재 라이더는 역삼역 사거리 부근에서 배달 중입니다. 예상 도착 시간은 2026년 5월 30일 오후 6시 24분 31초입니다."
  }
]
```

**콘솔 로그**

```
[Assistant] sessionId=default, message=2024-1234 어디쯤이에요?
[Tool] getDeliveryStatus(orderId=2024-1234)
LLM 호출 완료 — 7065ms | 입력 토큰: 1263 | 출력 토큰: 95 | 총 토큰: 1358
```

> 헤더 없는 요청이 `sessionId=default`로 들어왔다. `default` Memory에 그 대화가 쌓였다.
> 구버전 앱이나 헤더를 빠뜨린 클라이언트가 여럿이라면 서로 다른 고객의 대화가 `default` 세션에 섞인다.

---

### 시나리오 5 — Memory 삭제 후 맥락 소실

**요청**

```bash
# 1234 언급
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A5" \
  -d '{"message":"2024-1234 주문 상태 알려줘"}'

# Memory 삭제
curl -s -X DELETE http://localhost:8080/api/v1/session/cust-A5

# DELETE 직후 Memory 상태 확인 (새 질문 전)
curl -s http://localhost:8080/api/v1/session/cust-A5/messages | jq

# 삭제 후 "그거" 질문
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A5" \
  -d '{"message":"그거 언제 도착해요?"}'
```

**1회차 응답**

```
주문번호 2024-1234의 상태는 배달 중입니다. 주문한 음식은 허니콤보(1개)와 콜라 1.25L(1개)이며, 총 금액은 26,000원입니다. 예상 배달 시간은 2026년 5월 30일 오후 6시 24분입니다.
```

**삭제 전 Memory 상태**

```json
[
  {
    "type": "USER",
    "content": "2024-1234 주문 상태 알려줘"
  },
  {
    "type": "ASSISTANT",
    "content": "주문번호 2024-1234의 상태는 배달 중입니다. 주문한 음식은 허니콤보(1개)와 콜라 1.25L(1개)이며, 총 금액은 26,000원입니다. 예상 배달 시간은 2026년 5월 30일 오후 6시 24분입니다."
  }
]
```

**DELETE 직후 Memory 상태 (새 질문 전)**

```json
[]
```

**삭제 후 응답**

```
어떤 주문을 말씀하시는 건가요? 최근에 진행한 주문 번호를 알려주시면 도와드리겠습니다.
```

**삭제 후 Memory 상태 (`/api/v1/session/cust-A5/messages`)**

```json
[
  {
    "type": "USER",
    "content": "그거 언제 도착해요?"
  },
  {
    "type": "ASSISTANT",
    "content": "어떤 주문을 말씀하시는 건가요? 최근에 진행한 주문 번호를 알려주시면 도와드리겠습니다."
  }
]
```

**콘솔 로그**

```
[Assistant] sessionId=cust-A5, message=2024-1234 주문 상태 알려줘
[Tool] getOrderDetail(orderId=2024-1234)
LLM 호출 완료 — 6852ms | 입력 토큰: 1346 | 출력 토큰: 156 | 총 토큰: 1502

[Session] clear sessionId=cust-A5

[Assistant] sessionId=cust-A5, message=그거 언제 도착해요?
LLM 호출 완료 — 1283ms | 입력 토큰: 570 | 출력 토큰: 29 | 총 토큰: 599
```

> DELETE 후 cust-A5의 1234 대화가 사라졌다. "그거"에 맥락이 없으니 되묻기 응답이 나왔다.
> 삭제 후 Memory에는 새 대화만 남아 있다. 이전 이력은 완전히 사라졌다.
> 입력 토큰 570. 시나리오 4의 세션 B(빈 세션 첫 질문)와 같다. Memory가 완전히 초기화됐다는 증거다.
>
> 상담이 끝나면 세션을 정리하는 게 비용도 줄인다. JDBC로 전환하면 자동 정리를 위한 TTL 정책도
> 같이 설계해야 한다. 3단계에서 다시 나올 문제다.

---

## 회고

### 회고 1 — Memory가 있어도 Tool 재호출은 보장되지 않는다

시나리오 1, 3에서 2회차 질문에 Tool이 재호출되지 않았다. 처음엔 모델이 Memory에 답이 있으니 알아서 생략한다고 생각했다. 그런데 `ASSISTANT_PROMPT`를 다시 보니 이런 지시가 있었다.

```
이전 대화에서 이미 조회한 정보는 다시 도구를 호출하지 말고 대화 이력에서 재사용하세요.
```

모델의 자율적 판단이 아니라 프롬프트가 명시적으로 그렇게 시킨 것이다. (분리 경위는 회고 4 참조)

문제는 배달 위치나 예상 도착 시간이 매 분 바뀌는 실시간 데이터라는 점이다. Memory에서 꺼낸 "오후 6시 1분"이 지금도 맞는다는 보장이 없다. "이미 조회한 정보는 재사용하라"는 지시를 더 좁게 써야 한다. 위치·시간처럼 실시간성이 중요한 데이터는 재사용 대상에서 제외하는 예외 조항이 프롬프트에 있어야 한다.

**추가 검증 — 프롬프트 수정 후**

`ASSISTANT_PROMPT`에 실시간 데이터 예외 조항을 추가했다.

```
단, 배달 위치·도착 시간 등 실시간으로 변하는 데이터는 매번 도구를 호출하세요.
```

결과: 예외 조항을 명시했는데도 qwen2.5가 따르지 않았다. 2회차 "그거 몇시에 도착해요?"에서 여전히 Memory 재사용 쪽이 더 강하게 동작했다. 프롬프트 지시만으로는 해결이 어려운 모델 수준의 한계다.

프롬프트가 길어지면서 시나리오 1 당시 1273이었던 1회차 입력 토큰이 2728(+1455)로 늘었다. 프롬프트 자체도 토큰 비용에 직접 영향을 준다.

### 회고 2 — 토큰 증가는 Memory 크기보다 Tool 호출 여부에 더 달려 있었다

"Memory가 쌓일수록 입력 토큰이 늘어난다"는 게 일반 법칙인데, 시나리오 1·3에서는 2회차가 1회차보다 오히려 작았다. Tool 결과 JSON이 Memory 텍스트 2개보다 토큰을 더 많이 먹기 때문이다.

토큰 증가를 예측하려면 "Memory에 메시지가 몇 개냐"와 "이번 턴에 Tool이 호출됐냐"를 같이 봐야 한다. 시나리오 2에서 두 턴 모두 Tool이 호출돼서 절대적 토큰 수준이 1000 이상으로 높게 유지됐다. 1222 → 1409(+187) 증가는 2회차에 1회차 Memory 메시지 2개가 추가된 것이지, Tool 재호출 때문이 아니다. 2단계에서 10턴 시나리오를 돌릴 때 이 패턴이 더 복잡하게 나올 것 같다.

### 회고 3 — 세션 격리가 코드 한 줄에 달려 있다

시나리오 4의 격리가 동작하는 건 `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))` 한 줄 덕분이다. 이 줄 하나가 빠지면 모든 요청이 같은 Memory를 공유한다. 컴파일 에러도, 런타임 에러도, 예외도 없다. 기능 테스트를 짜지 않으면 프로덕션에서 터지기 전까지 발견이 어렵다.

세션 ID를 아는 사람이면 누구든 그 대화 이력에 접근할 수 있다는 점도 인식해둬야 한다. 실제 서비스에서 세션 식별은 인증과 함께 설계된다.

헤더 없는 요청이 `sessionId=default`로 들어갔다. 헤더를 안 보내는 클라이언트가 여럿이면 서로 다른 고객의 대화가 `default` 세션에 섞인다.

### 회고 4 — ASSISTANT_PROMPT를 분리한 이유와 결과

Round 2에서 `AssistantController`와 `SupportController`가 같은 `SYSTEM_PROMPT`를 쓰고 있었다. 그 상태에서 `AssistantController`로 Tool Calling을 실행하면 응답 말미에 이런 게 붙었다.

```
estimatedResolutionMinutes: 0
customerSentiment: NEUTRAL
requiresHumanAgent: false
```

`SYSTEM_PROMPT` 안에 `SupportController`용 JSON 포맷 명세가 포함돼 있어서 생긴 문제였다. `AssistantController`는 자연어 응답이 목적인데, 프롬프트가 JSON 필드까지 함께 출력하라고 지시하고 있었다. 목적이 다른 두 엔드포인트가 프롬프트를 공유하면 이렇게 된다.

사실 독립 프롬프트를 처음 적용한 건 Round 2였다. 당시 `AssistantController`에 인라인으로 단순 프롬프트를 달았고, 그 시점에 JSON 필드가 응답에서 사라졌고 Tool도 안정적으로 호출됐다. Round 3에서 한 건 그 인라인 문자열을 `BaedalPrompt.ASSISTANT_PROMPT` 상수로 정리하고 Memory 관련 지시를 추가한 것이다.

Round 3에서 `ASSISTANT_PROMPT` 상수로 정리하면서 두 가지 지시를 넣었다.

```
이전 대화에서 이미 조회한 정보는 다시 도구를 호출하지 말고 대화 이력에서 재사용하세요.
"그거", "방금 그 주문", "아까 말한 주문" 같은 지시 대명사는 가장 최근에 언급된 주문번호로 해석합니다.
```

지시 대명사 규칙 덕분에 시나리오 1~3의 "그거", "아까 물어본 그 주문"이 동작했다.

재사용 지시에서는 트레이드오프가 생겼다. 의도는 불필요한 Tool 재호출을 막는 것인데, 배달 위치 같은 실시간 데이터까지 재사용 대상으로 뭉뚱그려 들어갔다. 프롬프트 한 줄이 동작 범위를 너무 넓게 잡은 것이다.

참고로 tool-first 지시("도구 호출 전에 다른 텍스트를 출력하지 마세요")는 Round 2 인라인 프롬프트에 이미 있었던 것이다. Round 2에서 `cancelOrder` 호출 전에 한국어 텍스트가 먼저 나오면서 파싱이 깨지는 문제가 있었고, `@Tool` description 단축과 동시에 적용했다. 어느 쪽이 결정적이었는지는 분리해서 확인하지 못했다.

### 회고 5 — DELETE는 토큰도 리셋한다

시나리오 5에서 DELETE 후 입력 토큰이 570으로 돌아왔다. 시나리오 4의 세션 B(아무 Memory 없는 첫 질문)와 정확히 같다. Memory가 입력 토큰에 직접 영향을 준다는 걸 수치로 봤다.

상담이 끝나면 세션을 정리하는 게 비용도 줄인다. InMemory는 재시작하면 어차피 초기화되지만, JDBC로 전환하면 세션 TTL을 따로 관리해야 한다. 3단계에서 그 부분을 직접 다루게 될 것 같다.

---

## 설계 결정 문서

### MAX_MESSAGES = 20 선택 근거

배달 상담 평균 턴 수를 5~10턴으로 잡았다.

- 고객 메시지 1턴 + AI 응답 1턴 = 메시지 2개 소비
- 5턴 상담: 10개 메시지, 10턴 상담: 20개 메시지
- 20이면 USER/ASSISTANT를 합쳐 약 **10턴** 커버

| 크기 | USER+ASSISTANT 턴 수 | 평가 |
|---|---|---|
| 2 | 1턴 | 직전 발화만 기억 — "그거"가 바로 다음 턴에도 실패할 수 있음 |
| 10 | 5턴 | 짧은 상담은 커버하지만 긴 상담은 중간 맥락 소실 |
| **20** | **10턴** | 배달 상담 최대 길이를 여유 있게 커버 |
| MAX_VALUE | 무제한 | 입력 토큰이 세션 길이에 비례해 선형 증가 — 비용·지연 문제 |

현실적인 최대 길이 10턴을 커버하면서 토큰 한계도 없다. 20이면 충분하다.

---

### `X-Session-Id` 없을 때 `"default"` 폴백의 구체적 위험 시나리오

**위험 1 — 구버전 클라이언트**

앱 업데이트 전 구버전 클라이언트는 `X-Session-Id` 헤더를 보내지 않는다.
이 클라이언트를 사용하는 모든 고객이 `"default"` 세션을 공유한다.
고객 A의 "2024-1234 취소해줘" 발화가 고객 B의 다음 질문 맥락으로 주입될 수 있다.
개인정보(주문번호, 배달지 정보) 유출, 그리고 잘못된 주문 취소가 발생할 수 있다.

**위험 2 — 어뷰저 의도적 헤더 제거**

악의적 사용자가 헤더를 의도적으로 빼고 요청을 보내면 `"default"` 세션에 접근할 수 있다.
`"default"` 세션에 쌓인 다른 고객의 대화 이력을 간접적으로 참조하는 응답을 이끌어낼 수 있다.
예: 직전에 다른 고객이 "2024-9999 취소해줘"를 했다면, 어뷰저가 "그거 완료됐어?"라고 물었을 때 응답이 노출된다.

---

### 세션 식별 실무 대안 비교

| 방식 | 장점 | 단점 | 배달 상담 적합도 |
|---|---|---|---|
| **HTTP 헤더** (`X-Session-Id`) | 구현 단순, 명시적 | 클라이언트가 직접 생성 — 위·변조 가능 | 개발/내부용 적합, 프로덕션은 서버 발급 필요 |
| **쿠키** | 브라우저가 자동 전송, 만료 시간 설정 가능 | 앱에서는 별도 관리 필요, CSRF 위험 | 웹 기반 상담 채널에 적합 |
| **JWT 클레임** | 서버 서명 → 위·변조 불가, 사용자 ID 포함 가능 | 검증 로직 필요, 토큰 만료 관리 복잡 | 로그인 사용자 대상 배달 앱에 가장 적합 |
| **URL 경로** (`/session/{id}/chat`) | RESTful, 캐싱 가능 | URL에 세션 ID 노출, 공유 시 세션 공유됨 | 링크 공유가 빈번한 채널에는 위험 |

---

### 클라이언트가 세션 ID를 직접 정하는 방식의 보안 리스크

리스크는 두 가지다.

1. **타인 세션 탈취**: 예측 가능한 형식(예: `user-1234`)으로 발급하면 다른 사용자의 세션 ID를 추측해 그 대화 이력을 참조할 수 있다.
2. **세션 고정 공격**: 공격자가 미리 세션 ID를 정해두고 피해자가 그 ID를 쓰도록 유도하면, 피해자의 대화 이력을 공격자가 읽을 수 있다.

막는 방법:

| 방어 수단 | 설명 |
|---|---|
| 서버 발급 UUID | 로그인 시 서버가 UUID v4를 발급하고 클라이언트에 내려준다. 클라이언트는 이 값을 그대로 사용만 한다 |
| JWT 서명 검증 | 세션 ID를 서버의 비밀키로 서명된 JWT로 대체. 서버에서 매 요청마다 서명 검증 |
| 소유권 바인딩 | 세션 ID와 인증된 사용자 ID를 서버에서 매핑. 세션 ID가 맞아도 소유자 불일치 시 거부 |

---

## 자가 점검 체크리스트

- [x] `./gradlew bootRun`으로 프로젝트가 정상 실행됐다. `Started BaedalSupportApplication in 0.806 seconds`. `UnsupportedOperationException` 없음.
- [x] 시나리오 5종의 응답 본문 + Memory 상태 JSON이 모두 있다.
- [x] 시나리오 4에서 `/api/v1/session/cust-B4/messages`로 세션 B Memory에 cust-A4 대화가 없음을 확인했다.
- [x] 시나리오 5에서 `DELETE` 직후 `/api/v1/session/cust-A5/messages`가 `[]`를 반환했다.
- [x] `MAX_MESSAGES` 선택 근거 + 세션 ID 설계 결정 4개 질문의 답이 있다.
