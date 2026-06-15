# 1단계 설계: Tool 경계 설계 — OrderTools + AssistantController

**날짜**: 2026-05-24
**범위**: `OrderTools`, `OrderMockService`, `AssistantController`, `SupportController` 리팩토링
**핵심 목적**: Tool을 만드는 것이 아니라 **Tool의 경계(boundary)를 설계하고, 그 근거와 경계가 무너졌을 때의 결과를 기록하는 것**

---

## 설계 철학

> "판단은 LLM, 실행은 Spring Bean."

이 라운드의 모든 경계는 이 한 문장에서 출발한다. LLM이 결정할 수 없는 것(상태 변경, 비즈니스 규칙 적용, 보안 필터링)은 반드시 Spring Bean 안에 있어야 한다.

---

## 논의 과정에서 결정된 것들

### description 언어: 한국어 + 다국어 정확도 테스트

**논의 배경**: 강의 자료는 "한국어 description이 한국어 발화와의 의미 거리가 짧아 정확도가 높다"고 권장한다. 그런데 영어·일본어·중국어 사용자도 있지 않냐는 질문이 제기됐다.

**결론**: 한국어 우선, 단 다국어 정확도 통합 테스트로 관찰값을 측정한다.

| 상황 | 권장 description 언어 |
|---|---|
| 한국어만 사용 | 한국어 (의미 거리 최단) |
| 다국어 지원 필요 | 영어 (LLM 학습 데이터 기준으로 가장 범용) |
| 다국어 + 한국어 주력 | 한국어+영어 병기 `"주문 상세 조회 (Get order detail)"` |

**이번 설계**: 배달 앱 = 한국어 주언어이므로 한국어 단독으로 시작한다. `@Tag("multilingual")` 통합 테스트를 별도로 작성해 영어·일본어·중국어 발화에서의 Tool 호출 성공률을 수치로 기록한다. 이 관찰값은 3단계 description 실험과 연계된다.

---

### 멱등성이 왜 필요한가: LLM 기반 시스템은 동일 요청이 여러 번 오는 게 정상

**논의 배경**: "왜 cancelOrder에 멱등성이 필요한가?"라는 질문이 제기됐다.

LLM 기반 시스템은 **Tool 호출 횟수를 LLM이 자율적으로 결정**한다. 다음 상황에서 같은 Tool이 중복 호출된다.

**상황 A — LLM이 직접 중복 호출**
```
사용자: "아 취소 부탁드려요. 아 진짜 취소 맞아요. 한 번 더 확인해주세요."
LLM 해석: "취소 + 재확인 필요" → cancelOrder 두 번 호출
```

**상황 B — 네트워크 재시도**
```
1번째 요청: 서버에 도달해서 처리됨, 그런데 응답이 늦게 도착
클라이언트: timeout 판단 → 자동 재시도
2번째 요청: 서버에 도달 → 이미 취소된 주문에 cancelOrder 재실행
```

**상황 C — LLM의 multi-step reasoning**
```
LLM 내부 추론: "취소 요청했는데 결과가 불확실하다. 다시 확인하자."
→ 같은 Tool 연속 2회 호출
```

**핵심**: 일반 REST API는 "두 번 오면 버그"이지만, LLM 에이전트는 "두 번 오는 게 정상"이다. 이 차이가 멱등성 설계를 필수로 만든다.

---

## 경계 1: 도메인 모델 vs View DTO

### 경계가 어디에

`@Tool` 메서드는 내부 `Order` 객체를 직접 반환하지 않는다. 각 질문 유형마다 별도 View DTO를 반환한다.

```
Order (내부 도메인)         → Tool 경계 → View DTO (LLM에게 노출)

getOrderDetail  → OrderDetailView
getDeliveryStatus → DeliveryStatusView
cancelOrder     → CancelOrderResult
```

**`OrderDetailView` 필드 결정**

| 필드 | 포함 | 이유 |
|---|---|---|
| orderId | ✅ | 식별자 |
| storeName | ✅ | "어디서 시켰어요?" |
| items | ✅ | "뭐 시켰어요?" |
| totalAmount | ✅ | "얼마예요?" |
| status | ✅ | "지금 어떤 상태예요?" |
| orderedAt | ✅ | "언제 시켰어요?" |
| estimatedDeliveryAt | ✅ | "언제 와요?" |
| deliveryAddress | ❌ | 개인정보. 주문 상세 질문 답변에 불필요 |
| riderLocation | ❌ | DeliveryStatusView 담당. 역할 분리 |
| canceledReason | ❌ | CancelOrderResult 담당. 역할 분리 |
| canceledAt | ❌ | CancelOrderResult 담당 |

**원칙: 각 View DTO는 딱 하나의 고객 질문 유형에만 답한다.**

### 왜 이 위치인가

LLM은 Tool 응답을 통째로 프롬프트에 넣는다. `Order`를 그대로 반환하면 두 가지 문제가 생긴다.

1. **민감 정보 노출**: `deliveryAddress`(고객 집 주소), `riderLocation`(실시간 위치)가 LLM 입력으로 들어간다. LLM이 "라이더 번호는 010-xxxx입니다"라고 응답할 수 있다.
2. **입력 토큰 폭증**: "메뉴가 뭐야?" 질문에 취소 이력, 라이더 좌표까지 LLM이 읽는다.

### 경계가 무너지면

```java
// ❌ 경계 무너진 버전
public Order getOrderDetail(String orderId) {
    return orderService.findById(orderId).orElse(null);
}
```

- 라이더 위치·연락처가 LLM 컨텍스트에 포함됨
- LLM이 `deliveryAddress`를 보고 "고객 주소는 서울시 강남구 xxx입니다"라고 응답
- 같은 `canceledReason`이 주문 상세 조회에도 노출됨

---

## 경계 2: 판단(LLM) vs 실행(Spring Bean)

### 경계가 어디에

```
LLM이 하는 것              Spring Bean이 하는 것
──────────────────────     ────────────────────────────
어느 Tool을 부를지          실제 Map 조회
어떤 orderId를 넣을지       isCancelable() 판단 (도메인 규칙)
응답을 자연어로 변환         cancel() 상태 변경
                            로그 기록 (감사 trail)
```

**핵심**: `isCancelable()` 로직은 `Order` 도메인 모델 안에 있다. Tool 안에 `if (status == COOKING)` 을 직접 쓰지 않는다.

```java
// Order.java 안
public boolean isCancelable() {
    return status == OrderStatus.CREATED || status == OrderStatus.ACCEPTED;
}

// OrderTools.java 안
if (!order.isCancelable()) {
    return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE, "...");
}
// ← 여기서 비즈니스 규칙을 "물어보는" 것. 규칙 자체는 도메인에 있음.
```

### 왜 이 위치인가

비즈니스 규칙(언제 취소 가능한가)이 Tool 안에 있으면 LLM이 그 규칙을 바꿀 수 있다. description을 조작하거나 파라미터를 통해 규칙 우회가 가능해진다.

### 경계가 무너지면

```java
// ❌ 경계 무너진 버전 — LLM이 판단까지 넘겨받음
@Tool(description = "주문을 취소한다. LLM이 취소 가능 여부를 스스로 판단한다.")
public void cancelOrder(String orderId) {
    orderService.findById(orderId).ifPresent(o -> o.cancel("LLM 판단", LocalDateTime.now()));
}
```

- 취소 가능 여부를 LLM이 자연어 컨텍스트에서 추론 → 모델마다 결과가 다름
- 동일 주문, 동일 상태인데 어떤 날은 취소되고 어떤 날은 안 되는 현상

---

## 경계 3: cancelOrder 멱등성

### 경계가 어디에

`cancelOrder`는 동일 `orderId`로 여러 번 호출돼도 부작용이 한 번만 발생한다.

```java
// Outcome 4분기
NOT_FOUND       → 주문 없음. 아무것도 변경하지 않음.
ALREADY_CANCELED → 이미 취소됨. 아무것도 변경하지 않음. 현재 상태 반환.
NOT_CANCELABLE  → COOKING 이후 상태. 아무것도 변경하지 않음.
CANCELED        → 이번 호출에서 처음 취소. cancel() 실행.
```

**왜 예외(Exception)가 아닌 `ALREADY_CANCELED`인가**

| 처리 방식 | LLM 반응 | 고객 경험 |
|---|---|---|
| 예외 throw | "취소 실패" → "취소가 안 됐습니다" 오안내 | ❌ 혼란 |
| 조용히 무시 (void) | 상태 모름 → 방금 뭐가 일어났는지 설명 불가 | ❌ 불투명 |
| `ALREADY_CANCELED` 반환 | "이미 취소됨" → "이미 처리된 상태입니다" 정확 안내 | ✅ 명확 |

### 왜 이 위치인가

`canceledReason`과 `canceledAt`은 최초 취소 시점의 정보다. 두 번째 호출에서 덮어쓰이면 감사 trail이 훼손된다.

### 경계가 무너지면

```java
// ❌ ALREADY_CANCELED 분기 제거
public CancelOrderResult cancelOrder(String orderId, String reason) {
    Order order = orderService.findById(orderId).orElse(null);
    if (order == null) return new CancelOrderResult(orderId, NOT_FOUND, "...");
    // ALREADY_CANCELED 체크 없음
    if (!order.isCancelable()) return new CancelOrderResult(orderId, NOT_CANCELABLE, "...");
    order.cancel(reason, LocalDateTime.now()); // 두 번째 호출에서도 실행됨
    return new CancelOrderResult(orderId, CANCELED, "...");
}
```

**관찰될 사고들 (2단계 실험에서 직접 확인)**:
- `canceledReason`이 두 번째 호출의 reason으로 덮어씌어짐
- 결제 취소 API 연동 시 환불 중복 발생
- 사장님에게 취소 알림 두 번 전송
- 포인트/쿠폰 이중 환급
- LLM이 첫 번째와 두 번째 모두 "취소되었습니다"라고 응답 → 고객이 "취소가 두 번 됐나?" 혼란

---

## 경계 4: description 계약

### 경계가 어디에

`description`은 LLM이 Tool 호출 결정을 내릴 때 보는 **유일한 API 문서**다. 코드를 실행해보지 않고 이 텍스트만 읽고 판단한다.

**description 4요소 (필수)**

| 요소 | 이유 |
|---|---|
| **무엇을 하는가** | Tool 식별 |
| **언제 호출해야 하는가** | false negative 방지 (불려야 할 때 안 불림) |
| **입력 형식** | orderId 자리에 메뉴명을 채우는 오류 방지 |
| **실패 시 반환값** | null 반환 시에도 LLM이 "찾을 수 없습니다" 안내 가능 |

**언어 결정**: 한국어 (배달 앱 = 한국어 주언어)

다국어 지원이 필요해지면 → 영어 또는 한국어+영어 병기로 전환. 이 전환 타이밍을 측정하기 위해 다국어 정확도 통합 테스트를 함께 작성한다.

### 왜 이 위치인가

description이 코드와 어긋나면 (실제 동작과 다른 설명) LLM이 잘못된 판단을 내린다. 오래된 주석처럼 썩는다.

### 경계가 무너지면

3단계 실험에서 직접 관찰할 것:

| description 버전 | 예상 증상 |
|---|---|
| 정상 (A) | Tool 호출 성공 |
| 빈약 한 줄 (B) | 호출해야 할 때 안 호출됨 (recall 저하) |
| 실제와 다른 설명 (C) | 엉뚱한 Tool 호출 또는 Hallucination |

---

## 경계 5: ChatClient 빌드 시점

### 경계가 어디에

```java
// ✅ 생성자에서 한 번만 빌드
public AssistantController(Builder builder, PerformanceLoggingAdvisor advisor, OrderTools tools) {
    this.chatClient = builder
            .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
            .defaultAdvisors(advisor)
            .defaultTools(tools)
            .build();  // 애플리케이션 시작 시 한 번
}

@PostMapping
public String ask(@Valid @RequestBody ChatRequest req) {
    return chatClient.prompt().user(req.message()).call().content();
}
```

### 왜 이 위치인가

Spring AI의 `ChatClient.Builder`에 `.defaultTools()`는 내부 컬렉션에 **append** 한다. 핸들러 메서드 안에서 호출하면 매 요청마다 누적된다.

| 요청 횟수 | builder의 tool 목록 | 결과 |
|---|---|---|
| 1회 | `[getOrderDetail, getDeliveryStatus, cancelOrder]` | ✅ 정상 |
| 2회 | `[...3개..., getOrderDetail, getDeliveryStatus, cancelOrder]` | ❌ 이름 중복 예외 |

### 경계가 무너지면

```
IllegalStateException: Multiple tools with the same name 'getOrderDetail' found
```

두 번째 요청부터 500 에러. 에러 메시지의 `getOrderDetail1` suffix가 단서.

**`SupportController` 리팩토링 이유**: 기존 코드가 핸들러에서 `.defaultAdvisors()`를 매 요청마다 호출하고 있었다. Tool 추가 전에는 Advisor 중복이 있어도 동작했으나, Tool을 추가하면 즉시 이 버그가 드러난다. 생성자 패턴으로 선제 리팩토링한다.

---

## 구현 범위 (1단계)

### 새로 생성할 파일

```
도메인 모델
  Order.java            가변 클래스. cancel(), isCancelable() 포함
  OrderStatus.java      enum: CREATED, ACCEPTED, COOKING, DELIVERING, DELIVERED, CANCELED
  OrderItem.java        record: menuName, quantity, unitPrice
  OrderMockService.java @Service, ConcurrentHashMap, @PostConstruct seed()

View DTO (Tool 경계)
  OrderDetailView.java     7개 필드 (민감 필드 4개 제외)
  DeliveryStatusView.java  orderId, status, riderLocation(nullable), estimatedDeliveryAt
  CancelOrderResult.java   orderId, Outcome(enum), message

Tool + Controller
  OrderTools.java           @Component, 3개 @Tool 메서드, 한국어 description
  AssistantController.java  /api/v1/assistant, 생성자 빌드 패턴
```

### 수정할 파일

```
BaedalPrompt.java       [Tool 사용 규칙] 섹션 추가
SupportController.java  생성자 패턴 리팩토링 + defaultTools 추가
```

### Mock 데이터 6건

| orderId | status | 목적 |
|---|---|---|
| 2024-1234 | DELIVERING | 배달 위치 조회 시나리오 |
| 2024-1235 | CREATED | 취소 가능 시나리오 |
| 2024-1236 | DELIVERED | NOT_CANCELABLE 시나리오 |
| 2024-1237 | COOKING | NOT_CANCELABLE 시나리오 |
| 2024-1238 | CANCELED | ALREADY_CANCELED 멱등 테스트 (canceledReason 미리 채움) |
| 2024-1239 | ACCEPTED | 취소 후 재취소 ALREADY_CANCELED 관찰 |

---

## 다국어 정확도 테스트 설계

Tool 선택은 LLM이 결정하므로 실제 Ollama가 필요한 통합 테스트다.

```java
@SpringBootTest
@Tag("multilingual")   // Ollama 미실행 환경에서는 건너뜀
class OrderToolsMultilingualAccuracyTest {

    static Stream<Arguments> deliveryStatusQueries() {
        return Stream.of(
            arguments("ko", "주문번호 2024-1234 배달 어디쯤에 있어요?"),
            arguments("en", "Where is my order 2024-1234?"),
            arguments("ja", "注文番号2024-1234の配達状況を教えてください"),
            arguments("zh", "订单2024-1234现在在哪里？"),
            arguments("en-casual", "is order 2024-1234 on its way?")
        );
    }

    @ParameterizedTest
    @MethodSource("deliveryStatusQueries")
    void getDeliveryStatus_tool_is_called_regardless_of_language(String lang, String message) {
        // [Tool] getDeliveryStatus 로그 캡처 후 호출 여부 검증
        // 결과를 정량 표로 기록 (3단계 실험 연계)
    }
}
```

**관찰 목적**: 한국어 description 사용 시 비한국어 발화에서의 Tool 호출 성공률을 측정한다. 성공률이 임계값(예: 3/5) 아래로 떨어지면 description을 영어 또는 병기로 전환할 근거가 된다.

---

## 자가 점검 — 경계 설계 관점

| 체크 | 질문 |
|---|---|
| ☐ | `OrderDetailView`에서 뺀 필드의 이유를 설명할 수 있는가? |
| ☐ | `isCancelable()`이 Tool이 아닌 도메인 모델에 있는 이유를 설명할 수 있는가? |
| ☐ | ALREADY_CANCELED가 예외가 아닌 이유를 설명할 수 있는가? |
| ☐ | 2단계에서 ALREADY_CANCELED 분기 제거 후 canceledReason 덮어씌어짐을 직접 확인했는가? |
| ☐ | description 언어가 한국어인 이유와 다국어 시 변경해야 하는 조건을 설명할 수 있는가? |
| ☐ | 생성자 패턴으로 빌드하지 않으면 몇 번째 요청에서 깨지는지 설명할 수 있는가? |
