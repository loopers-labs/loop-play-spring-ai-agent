# Round 2 — 학습 기록

## 내가 배운 것

---

### 1. Tool Calling은 LLM을 두 번 호출한다


1차 호출: 사용자 질문 + 시스템 프롬프트 + Tool 스키마 → LLM이 어떤 Tool을 부를지 결정  
2차 호출: 1차 프롬프트 전체 + Tool 실행 결과 → LLM이 최종 답변 생성

PerformanceLoggingAdvisor가 찍는 1203 토큰은 2차 호출의 입력 토큰이다. 1차 호출은 별도로 노출되지 않는다. 토큰이 유독 높게 찍히면 Tool이 실제 호출된 것이다.

---

### 2. Tool 스키마가 매 요청마다 프롬프트에 붙는다

`@Tool` / `@ToolParam`을 Spring AI가 직렬화해서 LLM 프롬프트에 자동으로 포함한다. Tool이 실행 안 되는 요청에도 예외 없이 붙는다.

"안녕하세요" 한 마디: `/api/v1/chat` ~10토큰, `/api/v1/assistant` 467토큰. 차이 약 457토큰이 시스템 프롬프트 + Tool 3개 스키마다.

Tool이 실제 호출되면 1203토큰까지 올라간다. Tool 결과 JSON이 2차 호출에 추가되기 때문이다. Tool 하나 쓰는 것만으로 Round 1 대비 120배 토큰이 된다. Tool이 많을수록, Tool 결과가 클수록 비용이 올라간다.

---

### 3. Tool description은 질문이 모호할 때 Tool 선택을 결정한다

LLM은 Tool을 고를 때 함수명과 description을 같이 읽는다. 질문이 직접적이고 함수명이 명확하면 함수명이 더 강한 신호다. "배달 어디쯤이에요?"와 `getDeliveryStatus`는 description이 틀려도 매칭됐다.

description이 결정적인 건 질문이 모호할 때다. "상황이 어떻게 됐어요?"는 `getOrderDetail`과 `getDeliveryStatus` 둘 다 답할 수 있다. 전체 description이 있으면 `getOrderDetail` 5/5였고, 한 줄 description만 있으면 4:1로 흔들렸다.

잘못된 description은 Tool 선택에 영향을 못 줘도 응답 품질에는 영향을 줬다. "8시 28분 46총체간에 있습니다"가 그 증거다. 컴파일러가 검증하지 않으니 코드가 바뀌어도 description이 그대로 남는 게 진짜 위험이다.

description에서 가장 중요한 건 "언제 호출하는가"다. 비슷한 Tool이 여러 개 있을 때 모델이 어느 쪽을 고를지 이 항목이 가른다. 이번 실험에서 이걸 없앴더니 Tool 선택이 흔들렸다.

두 번째는 실패 시 반환값이다. null인지, 에러 메시지인지, enum인지 안 써두면 LLM이 결과를 받아도 어떻게 해석해야 할지 몰라 hallucination한다.

"무엇을 하는가"는 함수명이 명확하면 이미 절반 이상 전달된다. 입력 형식은 YYYY-XXXX처럼 특수한 제약이 있을 때만 추가하면 된다.

---

### 4. 멱등성은 코드 레벨에서 보장해야 한다

"이미 취소된 주문을 다시 취소 요청해도 에러가 아닌 ALREADY_CANCELED를 반환한다"는 게 멱등성이다.

ALREADY_CANCELED 분기를 제거하고 실험했다. LLM이 Tool을 실행하지 않고 의사코드만 출력했다. Tool 결과가 예외면 LLM은 fallback 못 한다. 입력 토큰도 1151에서 538로 떨어졌다. Tool이 실행 안 됐으니 2차 호출이 없었기 때문이다.

멱등성 보장은 서비스 레이어에 맡기면 안 된다. 서비스가 예외를 던지면 LLM이 받을 결과가 없어진다. Tool 메서드 안에서 직접 상태를 체크하고 의미 있는 값을 돌려줘야 한다.

---

### 5. Tool 결과는 예외가 아닌 값으로 표현해야 한다

예외를 던지면 LLM이 fallback할 기회가 없다. 실패도 값이다.

이번에 사용한 `CancelOrderResult.Outcome` 4가지:

| Outcome | 의미 |
|---|---|
| `CANCELED` | 정상 취소 |
| `ALREADY_CANCELED` | 이미 취소됨 (멱등성) |
| `NOT_CANCELABLE` | 조리 시작 이후 — 취소 불가 |
| `NOT_FOUND` | 존재하지 않는 주문번호 |

실패 상황을 Outcome으로 표현하면 LLM이 각각에 맞는 안내를 할 수 있다. "취소가 이미 됐다는 걸 확인했어요"와 "취소할 수 없는 상태예요"는 고객에게 다른 말이다.

---

### 6. `ChatClient.Builder`는 공유 빈이다

`@RequiredArgsConstructor`로 주입받으면 매 요청마다 새로 생성되는 줄 알았다. 틀렸다. 싱글톤 빈이라 `.defaultTools()`가 누적으로 쌓인다.

```
IllegalStateException: Multiple tools with the same name
```

생성자에서 `.build()`까지 끝내고 필드에 담아두면 해결된다.

---

### 7. `num-ctx`가 부족하면 Tool이 텍스트로 나온다

총 토큰이 2048 한계에 딱 걸리면 모델이 Tool 호출 응답 만들 공간이 없어서 raw JSON을 그냥 뱉는다.

```
입력 토큰: 1917 | 출력 토큰: 131 | 총 토큰: 2048  ← 정확히 한계
```

`num-ctx: 8192`로 올리니 해결됐다. PerformanceLoggingAdvisor가 없었다면 한참 헤맸을 것이다. 토큰 수를 보는 것 하나로 원인을 찾았다.

---

### 8. AI가 만든 코드는 description은 잘 썼는데 코드가 껍데기였다

Codex에게 취소 Tool을 만들게 했다. description은 "언제 호출하는가"와 "언제 호출하지 않는가"를 모두 포함해서 꽤 잘 썼다.

문제는 코드였다.

```java
return deliveryOrderService.cancelOrder(orderId, cancelReason);
```

한 줄. 로그 없음. 멱등성 없음. 서비스가 예외 던지면 끝이다.

description을 잘 쓰는 것과 코드가 방어하는 것은 다른 문제다. AI는 전자를 알고 있었고 후자는 몰랐다.

---

## 의문점

---

**Q1. Tool이 동시에 여러 개 호출될 때 순서는?**

지금은 Tool이 하나씩 순차 호출됐다. "2024-1234 주문 상세랑 배달 현황 둘 다 알려줘"처럼 두 Tool이 한 번에 필요할 때 Spring AI가 병렬로 실행하는지, 아니면 순차로 두 번 왕복하는지 확인 못 했다. 병렬이면 2차 호출이 언제 일어나는지도 궁금하다.

---

**Q2. Tool 결과 JSON 크기 제한은?**

Tool 결과가 프롬프트에 붙는다는 걸 이번에 봤다. 결과 JSON이 매우 크면(예: 주문 목록 100건) 어떻게 되는지 모른다. Spring AI가 잘라내는지, 그냥 통으로 넣는지, `num-ctx` 초과로 또 이상한 응답이 나오는지.

---

**Q3. description이 결정적인 경우를 언제 직접 확인할 수 있는가?**

→ **3단계 추가 실험으로 확인됨**. "주문 2024-1234 상황이 어떻게 됐어요?"라는 모호한 질문으로 3버전 × 5회 실험함.

- 버전 A (전체 description): `getOrderDetail` 5/5. "현재 상태를 물을 때 호출"이 "상황"과 맞아떨어졌다.
- 버전 B (한 줄 description): `getDeliveryStatus` 4/5, `getOrderDetail` 1/5. 단서가 없으니 결과가 흔들렸다.
- 버전 C (`getDeliveryStatus`만 "메뉴와 결제 금액만 반환"으로 오해 유발): `getOrderDetail` 5/5. 모델이 `getDeliveryStatus` description을 읽고 이 Tool은 질문과 맞지 않는다고 판단했다.

"언제 호출하는가"는 함수명이 질문을 구분 못할 때 LLM이 의존하는 기준이다.

---

## Round 3에 시도하고 싶은 것

---

**"그거 취소해줘" 같은 지시 대명사**

"주문번호 2024-1234 메뉴 알려줘" → "그거 취소해줘"에서 '그거'가 2024-1234를 가리킨다는 걸 지금 구조에서는 LLM이 알 수 없다. Chat Memory에 최근 orderId를 저장해두면 해결할 수 있을 것 같다.

---

**취소 가능 여부 먼저 확인하는 흐름**

"2024-1234 취소해줘" 요청이 오면 지금은 `cancelOrder` 바로 호출한다. 취소가 불가능한 상태면 NOT_CANCELABLE을 돌려준다. Memory가 있다면 "이전에 조리 중이라고 확인했던 주문"을 기억하고 바로 "취소 불가"를 답할 수도 있다.

---

**대화 문맥 기반 Tool 선택**

"어디쯤이에요?" 처럼 orderId 없이 물어봐도, 바로 직전 대화에서 언급된 주문번호를 꺼내 쓸 수 있으면 UX가 훨씬 좋아진다. Memory에 마지막 언급 orderId를 넣어두는 간단한 방법부터 시작해볼 수 있을 것 같다.
