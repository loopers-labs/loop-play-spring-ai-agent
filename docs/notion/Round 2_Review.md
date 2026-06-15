# Round 2 Code Review Guide

# 2주차 코드 리뷰 가이드

> **대상 미션**: Tool Calling으로 주문/배달 연동 (`@Tool`, 판단/실행 분리, 멱등성)
**핵심 리뷰 포인트**: Tool description 품질, 판단/실행 경계선, 멱등성 처리, DTO 설계(토큰 경제성), Outcome enum, Mock 서비스 분리, Observability
**실전 라운드에서 드러난 추가 자리**: LLM 호출 불안정성과 결정적 검증 채널 분리, `ChatClient` 빌더 누적(Round 1 함정의 재발), Round 1 prompt 구조 재검토, AI 코드 리뷰의 *원본 보존 + 1:1 매핑 + 운영 시나리오 설명* 합격 깊이
> 

---

## 리뷰 체크리스트

### 필수 확인

- [ ]  `./gradlew bootRun`으로 프로젝트가 정상 빌드/실행되는가 (`OrderMockService seeded — 6건` 로그 확인)
- [ ]  `/api/v1/assistant` 호출 시 Tool이 실제로 호출되는가 (`[Tool] getXxx(orderId=...)` 로그 확인)
- [ ]  `getOrderDetail` / `getDeliveryStatus` / `cancelOrder` 세 Tool이 모두 `@Tool`과 `@ToolParam` 어노테이션을 사용하고 있는가
- [ ]  `@Tool`의 `description`이 **무엇/언제/입력/실패** 네 요소를 포함하는가
- [ ]  `cancelOrder`에서 **이미 취소된 주문**을 다시 호출해도 예외 없이 `ALREADY_CANCELED`를 반환하는가 (멱등성)
- [ ]  Tool 응답이 내부 도메인 엔티티(`Order`)가 아니라 전용 View DTO(`OrderDetailView`, `DeliveryStatusView`, `CancelOrderResult`)를 반환하는가
- [ ]  실패 상황을 **예외 throw**가 아닌 **Outcome enum**이나 **null**로 표현하는가
- [ ]  Mock 서비스(`OrderMockService`)가 컨트롤러가 아니라 별도 Service 클래스로 분리되어 있는가
- [ ]  `BaedalPrompt.SYSTEM_PROMPT`에 `[Tool 사용 규칙]` 섹션이 추가되어 있는가
- [ ]  `AssistantController`와 `SupportController` **양쪽 모두**에 `.defaultTools(orderTools)`가 등록되어 있는가
- [ ]  Tool 진입 시점에 `log.info("[Tool] ...")`로 감사(Audit) 로그를 남기는가

### 심화 확인

- [ ]  `OrderDetailView`가 `Order`의 민감 필드(`deliveryAddress`, `canceledReason`, `canceledAt` 등)를 의도적으로 제외했는가 (토큰 경제성 + 보안)
- [ ]  `CancelOrderResult.Outcome`이 boolean이나 단일 enum이 아니라 4분기(`CANCELED/ALREADY_CANCELED/NOT_CANCELABLE/NOT_FOUND`)로 나뉘어 있는가
- [ ]  `description`을 한국어/영어 중 하나로 정하고 System Prompt와 **언어 일관성**을 맞췄는가
- [ ]  `cancelOrder`의 “판단 로직”(상태 체크)과 “실행 로직”(order.cancel 호출)이 LLM이 아닌 Java 쪽에 있는가
- [ ]  3버전 description 실험(기준/빈약/오해 유발)에서 호출 횟수 차이가 수치로 기록되어 있는가
- [ ]  1주차 대비 입력 토큰 증가량이 수치로 비교되어 있고, 원인(Tool 스키마 주입)이 설명되어 있는가
- [ ]  설계 결정 문서(README)에 “왜 View DTO를 분리했는가”, “왜 Outcome을 4개로 두었는가”, “왜 description을 이 언어로 썼는가”가 명시되어 있는가
- [ ]  AI가 생성한 Tool 코드의 프로덕션 결함 3가지 이상을 구체적으로 식별하고 있는가

---

## 흔한 실수 패턴

### 실수 1: description을 모호하게 작성하여 LLM이 Tool을 호출하지 않음

**문제 코드:**

```java
@Tool(description = "get order")
public OrderDetailView getOrderDetail(
        @ToolParam(description = "order id") String orderId) {
    return orderService.findById(orderId).map(this::toDetailView).orElse(null);
}
```

**개선 코드:**

```java
@Tool(description = """
        주어진 주문번호의 상세 정보를 조회한다.
        메뉴, 수량, 금액, 주문 상태, 예상 배달 완료 시각을 반환한다.
        주문번호는 "YYYY-XXXX" 형식이며 (예: 2024-1234),
        존재하지 않으면 null을 반환한다.
        """)
public OrderDetailView getOrderDetail(
        @ToolParam(description = "조회할 주문번호. 예: 2024-1234") String orderId) {
    ...
}
```

**왜?** `description`은 **LLM이 읽는 유일한 API 문서**입니다. “get order” 같은 한 줄 설명으로는 LLM이 “언제 이 Tool을 쓸지” 판단할 수 없어, 질문이 모호할 때 Tool을 아예 호출하지 않거나 엉뚱한 답변을 생성합니다. 좋은 description은 다음 네 가지를 포함해야 합니다:

1. **무엇을 하는가** — “주문 상세 정보를 조회한다”
2. **언제 써야 하는가** — “메뉴, 수량, 금액을 묻는 질문에 사용”
3. **입력 형식** — “주문번호는 YYYY-XXXX 형식”
4. **실패 시 동작** — “존재하지 않으면 null 반환”

3단계 실험(A/B/C 버전)에서 “빈약 버전”이 호출률 40~60%까지 떨어지는 것을 확인하면 이 원칙이 체감됩니다.

---

### 실수 2: Tool 메서드에서 도메인 엔티티(Order)를 그대로 반환

**문제 코드:**

```java
@Tool(description = "주문 상세 조회")
public Order getOrderDetail(@ToolParam(description = "주문번호") String orderId) {
    return orderService.findById(orderId).orElse(null);
}
```

**개선 코드:**

```java
@Tool(description = "...")
public OrderDetailView getOrderDetail(
        @ToolParam(description = "조회할 주문번호. 예: 2024-1234") String orderId) {
    return orderService.findById(orderId)
            .map(this::toDetailView)
            .orElse(null);
}

private OrderDetailView toDetailView(Order order) {
    var lines = order.items().stream()
            .map(i -> new OrderDetailView.Line(i.menuName(), i.quantity(), i.unitPrice()))
            .toList();
    // deliveryAddress, riderLocation 같은 민감/불필요 필드는 의도적으로 제외
    return new OrderDetailView(
            order.orderId(), order.storeName(), lines,
            order.totalAmount(), order.status().name(),
            order.orderedAt(), order.estimatedDeliveryAt()
    );
}
```

**왜?** `Order` 엔티티에는 LLM에게 보여줄 필요가 없는 필드들이 있습니다:

- **보안**: `deliveryAddress`, `canceledReason`, `canceledAt` — 고객에게 답변할 맥락이 아닌 내부 감사용 데이터
- **토큰 낭비**: LLM 호출에 전달되는 모든 값은 입력 토큰으로 잡힙니다. 15개 필드를 매번 전달하면 불필요한 비용/지연이 발생합니다
- **응집도**: 엔티티가 LLM 응답 스키마와 결합되면, 엔티티 리팩토링이 LLM 응답 포맷을 깨뜨립니다

Tool 응답은 항상 **LLM이 읽는 전용 View DTO**로 감싸는 것이 원칙입니다. `toDetailView()` 같은 변환기를 명시적으로 두면 “무엇을 노출하지 않을지”가 코드로 드러납니다.

---

### 실수 3: cancelOrder에서 멱등성 미처리 → 2회 호출 시 상태 오염

**문제 코드:**

```java
@Tool(description = "주문 취소")
public CancelOrderResult cancelOrder(
        @ToolParam(description = "주문번호") String orderId,
        @ToolParam(description = "사유") String reason) {

    Order order = orderService.findById(orderId).orElseThrow();
    if (!order.isCancelable()) {
        return new CancelOrderResult(orderId, NOT_CANCELABLE, "취소 불가");
    }
    // 이미 CANCELED 상태인 경우에 대한 분기가 없음
    order.cancel(reason, LocalDateTime.now());  // canceledReason이 덮어씌워진다
    return new CancelOrderResult(orderId, CANCELED, "취소되었습니다");
}
```

**개선 코드:**

```java
@Tool(description = """
        ...
        이미 취소된 주문을 다시 취소 요청하면 에러가 아닌 ALREADY_CANCELED 결과를 돌려준다.
        """)
public CancelOrderResult cancelOrder(
        @ToolParam(description = "취소할 주문번호. 예: 2024-1234") String orderId,
        @ToolParam(description = "고객이 말한 취소 사유") String reason) {

    Order order = orderService.findById(orderId).orElse(null);
    if (order == null) {
        return new CancelOrderResult(orderId, Outcome.NOT_FOUND, "해당 주문번호를 찾을 수 없습니다.");
    }

    // 멱등성: 이미 취소된 주문은 에러 없이 동일한 성공 응답을 돌려준다.
    if (order.status() == OrderStatus.CANCELED) {
        return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED,
                "해당 주문은 이미 취소된 상태입니다. (취소 사유: " + order.canceledReason() + ")");
    }

    if (!order.isCancelable()) {
        return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE,
                "조리가 이미 시작되어 자동 취소가 불가합니다.");
    }

    order.cancel(reason, LocalDateTime.now());
    return new CancelOrderResult(orderId, Outcome.CANCELED, "주문이 취소되었습니다.");
}
```

**왜?** LLM 기반 시스템에서는 **같은 요청이 여러 번 오는 것이 정상**입니다:

- 사용자가 “진짜 취소됐어요? 한 번 더 취소해주세요” 같은 재확인 요청을 보냄
- LLM이 타임아웃/재시도 로직에서 같은 Tool을 두 번 호출할 수 있음
- 네트워크 재시도로 동일 요청이 중복 전달될 수 있음

멱등성 분기(`if (order.status() == CANCELED)`)가 없으면:

- `order.cancel(reason, ...)`이 **두 번 실행**되어 `canceledReason`이 두 번째 사유로 덮어씌워짐
- LLM이 “취소되었습니다”라고 두 번 확정 답변 → 고객이 **두 번 취소된 것으로 오해**
- 프로덕션이라면 결제 이중 취소, 포인트 이중 환급, 사장님 알림 두 번 등 실제 장애로 연결됨

수강생이 2단계에서 이 분기를 직접 제거하고 관찰했는지 확인하세요. “멱등성 분기 제거 후 `canceledReason`이 어떻게 덮어씌워졌는가”를 로그로 캡처했다면 체감한 것입니다.

---

### 실수 4: 예외를 throw 하여 LLM이 상황을 이해하지 못함

**문제 코드:**

```java
@Tool(description = "주문 취소")
public void cancelOrder(@ToolParam(description = "주문번호") String orderId,
                        @ToolParam(description = "사유") String reason) {

    Order order = orderService.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

    if (!order.isCancelable()) {
        throw new IllegalStateException("취소 불가 상태: " + order.status());
    }
    order.cancel(reason, LocalDateTime.now());
}
```

**개선 코드:**

```java
public CancelOrderResult cancelOrder(String orderId, String reason) {
    Order order = orderService.findById(orderId).orElse(null);
    if (order == null) {
        return new CancelOrderResult(orderId, Outcome.NOT_FOUND, "...");
    }
    if (order.status() == OrderStatus.CANCELED) {
        return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED, "...");
    }
    if (!order.isCancelable()) {
        return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE, "...");
    }
    order.cancel(reason, LocalDateTime.now());
    return new CancelOrderResult(orderId, Outcome.CANCELED, "...");
}

public enum Outcome {
    CANCELED, ALREADY_CANCELED, NOT_CANCELABLE, NOT_FOUND
}
```

**왜?** Tool 메서드가 예외를 던지면:

1. Spring AI가 예외 스택트레이스를 ToolResponseMessage로 LLM에 전달하지만, **LLM은 비즈니스 맥락을 이해하지 못함**
2. 고객에게 “시스템 오류가 발생했습니다” 같은 무의미한 응답만 생성됨
3. LLM이 fallback(“상담원 연결”)할 판단 근거를 잃음

`Outcome` enum으로 실패 상황을 **값(value)**으로 표현하면:

- LLM이 `outcome=NOT_CANCELABLE`을 보고 “조리가 시작되어 취소 불가”를 고객 언어로 설명
- `outcome=ALREADY_CANCELED`를 보고 “이미 취소됨” 안내
- `outcome=NOT_FOUND`를 보고 “주문번호 다시 확인 요청”

**반환 타입을 void로 하는 것도 나쁜 신호입니다.** Tool은 항상 LLM이 후속 판단에 쓸 수 있는 값을 반환해야 합니다. `boolean`도 마찬가지 — `true/false`만으로는 “왜 실패했는지”를 LLM이 모릅니다.

> **예외 vs Outcome enum 원칙**: “LLM이 고객에게 설명해야 하는 비즈니스 상황” → Outcome enum. “진짜 시스템 장애(DB 연결 실패 등)” → 예외 허용 (단, 이 경우에도 상위에서 catch해 graceful message로 변환 권장)
> 

---

### 실수 5: Mock 로직을 Controller나 Tool에 직접 작성

**문제 코드:**

```java
@RestController
public class AssistantController {

    private final Map<String, String> orders = Map.of(
            "2024-1234", "교촌치킨 강남점, 허니콤보 23000원, DELIVERING",
            "2024-1235", "버거킹 선릉점, 와퍼 9500원, CREATED"
    );

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        // Tool 내부에서 orders.get()으로 조회 ...
    }
}
```

또는 `OrderTools` 안에 직접 Mock 데이터 Map을 둠:

```java
@Component
public class OrderTools {
    private final Map<String, Order> orders = new HashMap<>();  // Tool이 데이터 저장소 역할까지

    @Tool(...) public OrderDetailView getOrderDetail(String orderId) {
        Order o = orders.get(orderId);  // 직접 Map 조회
        ...
    }
}
```

**개선 코드:**

```java
// 1) 데이터 접근은 별도 Service
@Service
public class OrderMockService {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() { /* Mock 데이터 초기화 */ }

    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}

// 2) Tool은 Service에 위임
@Component
@RequiredArgsConstructor
public class OrderTools {
    private final OrderMockService orderService;

    @Tool(...) public OrderDetailView getOrderDetail(String orderId) {
        return orderService.findById(orderId).map(this::toDetailView).orElse(null);
    }
}

// 3) Controller는 LLM 흐름만 담당
@RestController
@RequiredArgsConstructor
public class AssistantController {
    private final ChatClient.Builder builder;
    private final OrderTools orderTools;
    // 주문 데이터는 모름. .defaultTools(orderTools)로만 연결
}
```

**왜?** 이 과정의 핵심 메시지 중 하나가 **“판단은 LLM, 실행은 Spring Bean”**입니다. 책임을 3단 분리해야 합니다:

| 계층 | 책임 | 2주차 구현 |
| --- | --- | --- |
| Controller | LLM 호출 흐름 조율 | `AssistantController` |
| Tool 클래스 | LLM이 부르는 메서드 노출 + Outcome 분기 | `OrderTools` |
| Service | 실제 데이터 접근 / 비즈니스 로직 | `OrderMockService` |

이렇게 분리하면:

- `OrderMockService`를 나중에 `OrderService`(JPA 기반 실제 구현)로 교체할 때 Tool 코드는 안 바뀜
- Tool 단위 테스트 작성 시 `OrderMockService`를 Mockito로 mock 가능
- 3주차(메모리), 4주차(RAG) 확장 시 Controller가 복잡해지지 않음

Controller가 비즈니스 데이터를 들고 있으면 **1주차로 되돌아간 것**입니다. 2주차 핵심 교훈을 놓친 것이므로 반드시 리팩토링하게 유도하세요.

---

### 실수 6: System Prompt에 Tool 사용 규칙을 명시하지 않음

**문제 코드:**

```java
public static final String SYSTEM_PROMPT = """
        당신은 배달 고객 상담 AI입니다.
        존댓말을 사용하고 타사 앱을 추천하지 마세요.
        """;
// [Tool 사용 규칙] 섹션 없음
```

증상: 사용자가 “주문번호 2024-1234 어디쯤이에요?”라고 물으면 Tool을 호출하지 않고 LLM이 **상상으로 “곧 도착합니다”** 같은 답변을 생성.

**개선 코드:**

```java
public static final String SYSTEM_PROMPT = """
        당신은 '배달' 고객 상담 AI 에이전트입니다.

        [역할]
        - 주문/배달/취소/환불 관련 고객 문의를 1차로 처리합니다.
        ...

        [Tool 사용 규칙]
        - 주문 상세, 배달 현황, 주문 취소는 반드시 제공된 Tool을 통해서만 수행합니다.
          (절대로 값을 추측하거나 상상하지 않습니다.)
        - getOrderDetail: 고객이 메뉴/금액/상태를 물을 때 사용합니다.
        - getDeliveryStatus: 고객이 "어디쯤 있어요?", "언제 와요?"를 물을 때 사용합니다.
        - cancelOrder: 고객이 명시적으로 취소를 요청할 때만 호출합니다.
          Tool 결과의 outcome 필드를 보고 고객에게 맞게 설명합니다.
        - Tool이 null을 돌려주면 "해당 주문번호를 찾을 수 없다"고 안내합니다.

        [응답 포맷]
        - 3문장 이내로 요약 → 필요한 추가 정보 요청 → 다음 액션 제안
        """;
```

**왜?** Tool을 `.defaultTools(orderTools)`로 등록하는 것만으로는 LLM이 항상 Tool을 쓰지 않습니다. LLM은 다음과 같이 판단합니다:

1. “이 Tool이 지금 이 질문에 쓸 만한가?” (description 기반)
2. “Tool을 안 쓰고도 대답할 수 있지 않을까?” (내부 지식 기반 hallucination 위험)

System Prompt에서 **“반드시 Tool을 통해서만 수행, 값을 상상하지 말 것”**을 명시해야 할루시네이션을 줄일 수 있습니다. 또 각 Tool이 **언제 적합한지**를 System Prompt에서 한 번, Tool description에서 다시 한 번 반복 명시하면 호출 정확도가 올라갑니다.

> **리뷰 팁**: 수강생이 `[Tool 사용 규칙]` 섹션을 뺐을 때 실제로 할루시네이션이 발생하는지 실험해보게 하세요. `[금지] 섹션 제거 실험`(1주차)과 같은 논리입니다.
> 

---

### 실수 7: Tool 호출 로깅이 없어 감사(Audit) 불가

**문제 코드:**

```java
@Tool(description = "...")
public CancelOrderResult cancelOrder(
        @ToolParam(description = "...") String orderId,
        @ToolParam(description = "...") String reason) {
    // 로그 없음 — 누가/언제/무엇을 취소했는지 추적 불가
    Order order = orderService.findById(orderId).orElse(null);
    ...
}
```

**개선 코드:**

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    @Tool(description = "...")
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "...") String orderId,
            @ToolParam(description = "...") String reason) {

        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);

        Order order = orderService.findById(orderId).orElse(null);
        ...
    }
}
```

**왜?** Tool은 **“LLM이 판단한 결과를 외부 세계에 실행하는 경계선”**입니다. 이 경계에서 로그를 남기지 않으면:

- “고객이 취소 요청을 한 적이 없다는데 왜 취소되었나?” 같은 문의가 왔을 때 LLM이 Tool을 호출한 이력을 추적 불가
- 테스트 중 “LLM이 Tool을 호출하지 않았다”는 현상을 로그로 확인 불가 (3단계 description 실험의 기초)
- 프로덕션에서 이상 호출 패턴(한 주문번호에 10회 cancelOrder 등) 탐지 불가

최소한 **진입 시점에 파라미터를 로깅**하고, 중요한 Tool(`cancelOrder` 같은 변경 작업)은 **결과도 로깅**하는 것이 원칙입니다. 이 로그는 3단계 description 실험에서도 “Tool이 호출되었는가”를 판정하는 기준이 됩니다.

---

### 실수 8: Outcome enum이 boolean 수준이거나 세분화가 부족함

**문제 코드:**

```java
public record CancelOrderResult(String orderId, boolean success, String message) {}
// 또는
public enum Outcome { SUCCESS, FAIL }
```

**개선 코드:**

```java
public record CancelOrderResult(String orderId, Outcome outcome, String message) {
    public enum Outcome {
        CANCELED,            // 이번 호출에서 취소됨
        ALREADY_CANCELED,    // 이미 취소되어 있었음 (멱등 — 에러 아님)
        NOT_CANCELABLE,      // 조리 시작 이후 등 취소 불가
        NOT_FOUND            // 주문번호 없음
    }
}
```

**왜?** `boolean success`나 `SUCCESS/FAIL` 2분기는 **LLM이 고객에게 설명할 근거가 없습니다**. “취소 실패”만으로는 LLM이 다음 중 무엇을 답해야 할지 모릅니다:

- “주문번호를 다시 확인해주세요” (NOT_FOUND)
- “이미 취소된 상태입니다” (ALREADY_CANCELED)
- “조리가 시작되어 상담원 연결이 필요합니다” (NOT_CANCELABLE)

각 분기마다 고객에게 **다른 안내 + 다른 다음 액션**이 필요하므로 Outcome을 세분화해야 합니다. 반대로 너무 세분화(10개 이상)하면 LLM이 혼란스러워하고 경계 케이스에서 일관성이 떨어집니다. **4~6개가 적정선**입니다.

수강생이 2단계에서 “Outcome을 4개로 둔 이유 + 추가로 상상해본 신규 Outcome 2개”를 README에 썼는지 확인하세요. 예: `REQUIRES_AGENT`(상담원 수동 취소 필요), `COOLING_OFF`(방금 취소한 주문을 또 요청) 등.

---

### 실수 9: ToolParam에 description 누락 또는 영문 한 단어

**문제 코드:**

```java
@Tool(description = "주문 상세 조회")
public OrderDetailView getOrderDetail(String orderId) {  // @ToolParam 없음
    ...
}

// 또는
@Tool(description = "배달 상태 조회")
public DeliveryStatusView getDeliveryStatus(
        @ToolParam(description = "id") String orderId) {  // 너무 짧음
    ...
}
```

**개선 코드:**

```java
@Tool(description = "...")
public OrderDetailView getOrderDetail(
        @ToolParam(description = "조회할 주문번호. 예: 2024-1234") String orderId) {
    ...
}
```

**왜?** LLM이 Tool을 호출할 때 **파라미터 값을 어떻게 채울지**는 `@ToolParam.description`과 JSON 스키마를 보고 결정합니다. 없거나 모호하면:

- 사용자 입력에서 주문번호 추출 실패 (`"1234번 주문"` → orderId에 `"1234"`만 전달, `"2024-1234"` 기대했는데 불일치)
- 파라미터 순서/타입을 엉뚱하게 매핑
- 빈 문자열/null 전달

**최소한 “무엇인지 + 예시 형식”**을 적어야 합니다. `"조회할 주문번호. 예: 2024-1234"`처럼 format hint를 포함하면 LLM이 정확한 값을 추출합니다.

> ⚠️ **함정**: `@ToolParam(description = "... 예: '집 앞에 사람이 없어요'")` 처럼 *구체적 예시 문자열*을 넣으면, **사용자 발화에 사유가 없는 시나리오에서 LLM이 그 예시 문자열을 그대로 파라미터에 박아 호출**하는 경우가 있습니다. 호출률 9/10인데 데이터는 *9건 전부 동일 가짜 사유*인 패턴. 예시는 *추상적 형식*으로 (`"고객이 말한 사유. 사유가 없으면 빈 문자열"`).
> 

---

### 실수 10: Tool 호출 불안정성을 *발화 강화*나 *prompt 강제*로만 우회 — 결정적 검증 채널 부재

**증상:**

수강생 README에 *“qwen2.5가 가끔 cancelOrder를 안 불러요”*, *“발화를 길게 했더니 호출됐어요”*, *“System Prompt에 강제하라고 적었더니 호출률이 올라갔어요”* 같은 보고가 있지만, **Outcome 4분기 검증 / 멱등성 ablation의 결정적 결과**가 없습니다. LLM 호출률이 시나리오마다 20~100%로 변동하는 상태에서 *비즈니스 로직이 진짜 맞게 동작하는지* 검증이 불가능합니다.

**문제 코드 / 검증 방식:**

```bash
# 5번 호출 → 그중 3번만 Tool 호출됨 → "잘 됩니다" 로 결론
curl -X POST /api/v1/assistant -d '{"message":"2024-1239 취소"}'
curl -X POST /api/v1/assistant -d '{"message":"2024-1239 취소"}'  # 이번엔 Tool 안 부름
curl -X POST /api/v1/assistant -d '{"message":"2024-1239 취소"}'
```

`ALREADY_CANCELED` 분기가 정말 동작하는지, `canceledReason`이 정말 덮어쓰이는지 — *LLM이 호출을 안 했으니* 검증 자체가 일어나지 않습니다.

**개선 — 결정적 검증 채널 분리:**

```java
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderTools orderTools;  // Tool 메서드를 직접 호출

    @PostMapping("/orders/{id}/cancel")
    public CancelOrderResult cancel(@PathVariable String id, @RequestBody CancelRequest req) {
        return orderTools.cancelOrder(id, req.reason());
    }
}
```

이렇게 분리하면 **두 채널의 책임이 달라집니다**:

| 채널 | 목적 | 검증 |
| --- | --- | --- |
| `/api/v1/assistant` | LLM 자연어 호출률 측정 | 시나리오 5종, description A/B/C |
| `/api/v1/admin/orders/{id}/cancel` | Outcome 4분기 / 멱등성 ablation | 비결정성을 우회한 결정적 검증 |

**왜?** LLM 비결정성은 라운드 1부터 6까지 사라지지 않는 변수입니다. *“발화를 어떻게 해야 호출되는가”* 와 *“호출됐을 때 비즈니스 로직이 맞는가”* 는 분리해서 평가해야 합니다. 발화 강화로 호출률을 100%로 끌어올리는 것은 가능하지만, 그 자체는 *비즈니스 로직 검증이 아닙니다*. 라운드 5에서 *상담원 fallback* 정책의 토대가 됩니다 — *“LLM이 안 부른 경우”* 도 처리해야 한다는 인식.

> **리뷰 팁**: 호출률이 50% 이하인데 *“잘 됩니다”* 로 결론지은 PR은 Request Changes. *“결정적 검증을 어떻게 했는가”* 를 묻고, admin endpoint 분리 또는 Tool 단위 테스트(Mockito) 둘 중 하나는 갖춰야 합니다.
> 

---

### 실수 11: `ChatClient` 빌더를 *매 요청마다* `.build()` — Round 1 함정의 재발

**문제 코드:**

```java
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final ChatClient.Builder builder;  // Spring이 주입하는 빌더 (싱글톤)
    private final OrderTools orderTools;

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        return builder                              // ❌ 매 요청마다
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)  // ❌ defaultSystem 누적
                .defaultTools(orderTools)           // ❌ defaultTools 누적
                .build()
                .prompt().user(req.message()).call().content();
    }
}
```

**증상:**

- 첫 요청은 정상, 시간이 지날수록 입력 토큰이 *기하급수적으로 증가*
- Tool이 *두 번* 등록되어 LLM 카탈로그에 중복으로 표시
- 멀티턴 / 멀티 사용자 환경에서 갑자기 응답이 느려짐

**왜?** `ChatClient.Builder`는 **stateful**입니다. `.defaultSystem()` / `.defaultTools()` / `.defaultAdvisors()` 는 호출할 때마다 빌더 내부 컬렉션에 *누적*됩니다. Spring이 주입하는 빌더는 싱글톤이라 *같은 컬렉션*에 계속 쌓이는 거예요.

**개선 코드:**

```java
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;  // 완성된 ChatClient를 보관
    private final OrderTools orderTools;

    public AssistantController(ChatClient.Builder builder,
                               PerformanceLoggingAdvisor advisor,
                               OrderTools orderTools) {
        this.orderTools = orderTools;
        this.chatClient = builder                  // ✅ 생성자에서 단 한 번
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(advisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        return chatClient.prompt().user(req.message()).call().content();
    }
}
```

> 💡 **Round 1 함정의 재발**: 1주차 코드 리뷰 가이드의 실수 1번이 같은 패턴이었습니다. *컨트롤러 단위 고정 설정은 생성자에서 단 한 번 build* — 라운드를 가로질러 일관된 규칙입니다. Round 3에서는 `.defaultAdvisors(memoryAdvisor)` 가 같은 자리에 들어가며 같은 함정을 만듭니다.
> 

**리뷰 팁**: `@RestController` 클래스 안에서 `builder.defaultXxx(...).build()` 가 `@PostMapping` 메서드 안에 있으면 무조건 Request Changes. 생성자 또는 `@PostConstruct` 로 이동시켜야 합니다.

---

### 실수 12: Round 1의 prompt 분리/통합 결정을 Round 2에서 재검토하지 않음

**증상:**

Round 1에서 `SYSTEM_PROMPT` / `STREAMING_PROMPT` / `STRUCTURED_PROMPT` 로 분리했던 prompt 구조를 Round 2에서 *그대로 둔* PR. Tool 호출률이 낮게 측정됐는데 원인을 모름. 다른 분의 PR에서는 *“prompt를 단일로 통합했더니 Tool 호출률이 60% → 80%로 올랐다”* 라는 회고가 보임.

**문제 패턴:**

```java
public class BaedalPrompt {
    public static final String SYSTEM_PROMPT = """
        [역할] ... [규칙] ... [응답 포맷: 구조화된 JSON으로 반환]
        """;  // ❌ Structured Output 가정이 박혀 있음

    public static final String STREAMING_PROMPT = """
        [역할] ... [규칙] ... [응답: 자연스러운 문장으로 답변]
        """;
}

// SupportController는 STRUCTURED_PROMPT 사용 (Structured Output)
// AssistantController는 STREAMING_PROMPT 사용 (Tool Calling) ← 여기서 충돌
```

**무엇이 충돌하는가:**

- Round 2에서 추가한 `[Tool 사용 규칙]` 섹션이 어느 prompt에 들어가야 할지 모호
- 두 prompt를 모두 수정해야 하는데 한쪽만 고침 → 한 엔드포인트에서만 Tool 호출됨
- *“구조화된 JSON으로 반환”* 규칙이 Tool 응답의 자연어 풀이와 충돌

**개선:**

선택지는 두 가지. *데이터로 결정*해야 합니다.

| 선택 | 조건 | 트레이드오프 |
| --- | --- | --- |
| **통합** | Tool 호출률이 최우선, 컨트롤러 간 응답 톤 차이 작음 | Structured Output 안정성 약간 손해 |
| **분리 유지** | Structured Output / Streaming / Tool Calling의 응답 형식 차이가 명확 | 각 prompt마다 `[Tool 사용 규칙]` 중복 관리 부담 |

**합격선:**

PR이 *prompt 구조를 Round 2 시작 시점에 한 번 검토했는가* — README에 *“통합 vs 분리 중 어느 것을 택했고 왜 그랬는지”* 가 적혀 있어야 합니다. 검토 없이 Round 1 구조를 그대로 쓴 PR은 **Comment** (정보 요청).

> **리뷰 팁**: Round 2의 *prompt 의사결정 재검토*는 라운드 4(RAG context 추가) / 라운드 5(Guardrail 규칙 추가) 에서도 매번 반복됩니다. *“기존 구조를 그대로 두는 결정도 결정이다”* — 그 결정의 근거를 묻는 게 리뷰의 일관된 자리.
> 

---

## 모범 답안

핵심 구현 부분의 모범 답안입니다. 전체 코드는 `week2/example-code/` 참조.

### OrderTools – @Tool + 판단/실행 분리 + Outcome enum

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderMockService orderService;

    @Tool(description = """
            주어진 주문번호의 상세 정보를 조회한다.
            메뉴, 수량, 금액, 주문 상태, 예상 배달 완료 시각을 반환한다.
            주문번호는 "YYYY-XXXX" 형식이며 (예: 2024-1234),
            존재하지 않으면 null을 반환한다.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "조회할 주문번호. 예: 2024-1234") String orderId) {

        log.info("[Tool] getOrderDetail(orderId={})", orderId);
        return orderService.findById(orderId).map(this::toDetailView).orElse(null);
    }

    @Tool(description = """
            주어진 주문번호의 주문을 취소한다.
            취소 가능 조건: 주문 상태가 CREATED 또는 ACCEPTED인 경우에만 가능.
            조리가 이미 시작된(COOKING 이후) 주문은 취소할 수 없다.
            이미 취소된 주문을 다시 취소 요청하면 에러가 아닌 ALREADY_CANCELED 결과를 돌려준다.
            결과는 항상 CancelOrderResult 객체로 반환되며, outcome 필드에서 성공/실패 사유를 확인할 수 있다.
            """)
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "취소할 주문번호. 예: 2024-1234") String orderId,
            @ToolParam(description = "고객이 말한 취소 사유. 예: '집앞에 사람이 없어요'") String reason) {

        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);

        Order order = orderService.findById(orderId).orElse(null);
        if (order == null) {
            return new CancelOrderResult(orderId, Outcome.NOT_FOUND,
                    "해당 주문번호를 찾을 수 없습니다.");
        }
        if (order.status() == OrderStatus.CANCELED) {  // 멱등성
            return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED,
                    "해당 주문은 이미 취소된 상태입니다. (취소 사유: " + order.canceledReason() + ")");
        }
        if (!order.isCancelable()) {
            return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE,
                    "조리가 이미 시작되어(" + order.status() + ") 자동 취소가 불가합니다.");
        }

        order.cancel(reason, LocalDateTime.now());
        return new CancelOrderResult(orderId, Outcome.CANCELED, "주문이 취소되었습니다.");
    }

    // ------- 변환기: 도메인 → LLM용 View -------
    private OrderDetailView toDetailView(Order order) {
        var lines = order.items().stream()
                .map(i -> new OrderDetailView.Line(i.menuName(), i.quantity(), i.unitPrice()))
                .toList();
        // deliveryAddress, riderLocation 등은 의도적으로 제외
        return new OrderDetailView(
                order.orderId(), order.storeName(), lines,
                order.totalAmount(), order.status().name(),
                order.orderedAt(), order.estimatedDeliveryAt()
        );
    }
}
```

**설계 포인트:**

- **판단/실행 분리**: Tool은 상태 체크(“취소 가능한가?”)까지만 담당. 실제 상태 변경은 `order.cancel()` 도메인 메서드에 위임
- **멱등성**: `if (order.status() == CANCELED)` 분기가 두 번째 호출에서 `order.cancel()` 재실행을 막음
- **Outcome 4분기**: LLM이 고객에게 다른 안내를 할 수 있도록 실패 사유를 값으로 표현
- **description 4요소**: 무엇(상세 조회) / 언제(메뉴/금액/상태 질문) / 입력(YYYY-XXXX) / 실패(null 반환)
- **View DTO 변환**: `toDetailView`에서 민감 필드 필터링 + 토큰 절감

### OrderDetailView – 토큰 경제성을 고려한 Tool 응답 DTO

```java
public record OrderDetailView(
        String orderId,
        String storeName,
        List<Line> items,
        int totalAmount,
        String status,
        LocalDateTime orderedAt,
        LocalDateTime estimatedDeliveryAt
        // deliveryAddress, riderLocation, canceledReason 등은 의도적으로 제외
) {
    public record Line(String menuName, int quantity, int unitPrice) {}
}
```

**설계 포인트:**

- `record`로 불변 DTO
- 중첩 `Line` record로 응집도 확보
- **“무엇을 뺐는가”**가 코드로 드러남 → 코드 리뷰에서 이 선택을 재검토 가능
- 토큰 입장에서 꼭 필요한 필드만 유지: 15개 필드 전체를 보내면 `{ "items": [...], "deliveryAddress": "...", ... }` 토큰이 2배로 뛴다

### CancelOrderResult – 실패를 값으로 표현

```java
public record CancelOrderResult(
        String orderId,
        Outcome outcome,
        String message
) {
    public enum Outcome {
        CANCELED,            // 이번 호출에서 취소됨
        ALREADY_CANCELED,    // 이미 취소되어 있었음 (멱등 — 에러 아님)
        NOT_CANCELABLE,      // 조리 시작 이후 등 취소 불가
        NOT_FOUND            // 주문번호 없음
    }
}
```

**설계 포인트:**

- `Outcome` enum이 4분기로 **고객 안내 메시지 분기**를 강제
- `message` 필드에 LLM이 그대로 인용 가능한 자연어 설명 포함
- `boolean success` 대신 enum — 확장성(신규 Outcome 추가)과 표현력 확보

### OrderMockService – 판단/실행 분리의 실행 쪽

```java
@Slf4j
@Service
public class OrderMockService {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() { /* Mock 주문 6건 초기화 */ }

    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
```

**설계 포인트:**

- Tool이 아닌 **Service에 데이터 접근을 격리** → JPA 도입 시 Tool은 안 바뀜
- `Optional<Order>` 반환 → Tool 쪽에서 `.map().orElse(null)`로 우아하게 처리
- 실제 서비스에서는 `OrderRepository`를 주입받는 진짜 `OrderService`로 교체

### BaedalPrompt – Tool 사용 규칙 포함 System Prompt

```java
public static final String SYSTEM_PROMPT = """
        당신은 '배달' 고객 상담 AI 에이전트입니다.

        [역할] ...
        [규칙] ...
        [금지] ...

        [Tool 사용 규칙]
        - 주문 상세, 배달 현황, 주문 취소는 반드시 제공된 Tool을 통해서만 수행합니다.
          (절대로 값을 추측하거나 상상하지 않습니다.)
        - getOrderDetail: 고객이 메뉴/금액/상태를 물을 때 사용합니다.
        - getDeliveryStatus: 고객이 "어디쯤 있어요?", "언제 와요?"를 물을 때 사용합니다.
        - cancelOrder: 고객이 명시적으로 취소를 요청할 때만 호출합니다.
          Tool 결과의 outcome 필드를 보고 고객에게 맞게 설명합니다.
        - Tool이 null을 돌려주면 "해당 주문번호를 찾을 수 없다"고 안내합니다.

        [응답 포맷] ...
        """;
```

**설계 포인트:**

- `[Tool 사용 규칙]`을 별도 섹션으로 분리 → Prompt Lab에서 제거/추가하며 효과 측정 가능
- “추측하지 않습니다” 명시 → 할루시네이션 차단
- 각 Tool마다 **“언제 쓸지”**를 System Prompt에서 한 번 더 강조 → Tool description과 이중 안전망

### AssistantController – Tool 연결

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;
    private final OrderTools orderTools;

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .defaultTools(orderTools)  // 2주차 핵심 한 줄
                .build()
                .prompt()
                .user(req.message())
                .call()
                .content();
    }
}
```

**설계 포인트:**

- `.defaultTools(orderTools)` 한 줄로 Tool 파이프라인 등록 (Spring AI가 리플렉션으로 `@Tool` 스캔)
- Controller는 LLM 호출 조율만 담당 — 주문 데이터는 모름
- `AssistantController`(평문 응답)와 `SupportController`(Structured Output) **양쪽 모두** 동일한 `.defaultTools()` 적용

---

## 리뷰 코멘트 예시

| 상황 | 코멘트 예시 |
| --- | --- |
| description이 “get order” 한 줄 | “description이 너무 짧아요. LLM에게는 이게 유일한 API 문서입니다. **무엇/언제/입력/실패** 네 요소를 포함해서 한국어로 풀어써보세요. 강의 노트의 `getOrderDetail` description을 참고하면 좋습니다. 3단계 실험에서 짧은 description이 호출률을 얼마나 떨어뜨리는지 직접 확인해보세요.” |
| Tool 응답으로 Order 엔티티 반환 | “Tool 응답에 `Order` 엔티티를 그대로 쓰고 계시네요. `deliveryAddress`나 `canceledReason` 같은 필드까지 LLM에 전달되면 (1) 민감 정보 노출 (2) 입력 토큰 낭비가 발생합니다. `OrderDetailView` 같은 전용 DTO를 만들어서 ’무엇을 노출하지 않을지’를 코드로 명시해보세요.” |
| cancelOrder에 ALREADY_CANCELED 분기 없음 | “`order.status() == CANCELED` 분기가 빠져 있어요. 이 상태로 같은 주문에 cancelOrder를 두 번 호출하면 `canceledReason`이 덮어씌워지고, LLM이 ’취소되었습니다’를 두 번 확정 답변하여 고객이 두 번 취소된 것으로 오해할 수 있습니다. 멱등성 분기를 추가하고, 2단계에서 이 분기를 제거했을 때의 관찰을 README에 써보세요.” |
| Tool에서 예외 throw | “주문을 못 찾았을 때 `OrderNotFoundException`을 던지고 계시네요. 예외가 LLM에 전달되면 고객에게 ‘시스템 오류입니다’ 같은 무의미한 답변만 나옵니다. `CancelOrderResult.Outcome.NOT_FOUND`처럼 **값으로 표현**하면 LLM이 ’주문번호 다시 확인해주세요’라고 고객 언어로 설명할 수 있어요.” |
| Outcome이 boolean | “`CancelOrderResult`에 `boolean success`만 있네요. 실패 사유가 `NOT_FOUND`인지 `NOT_CANCELABLE`인지 `ALREADY_CANCELED`인지에 따라 LLM이 고객에게 **다른 안내**를 해야 합니다. 4분기 enum으로 분리해보세요. 더 나아가, ’왜 4개로 두었는가’와 ’추가로 상상해본 신규 Outcome 2개’를 README에 적으면 설계력이 드러납니다.” |
| Mock 데이터가 Tool 클래스에 섞여 있음 | “`OrderTools`가 Map을 직접 들고 있네요. ’판단은 LLM, 실행은 Spring Bean’이 이번 주 핵심 메시지인데, Tool이 저장소 역할까지 하면 경계선이 무너집니다. `OrderMockService`로 데이터 접근을 분리하고, Tool은 서비스에 위임하게 바꿔보세요. 나중에 JPA로 교체할 때 Tool 코드가 안 바뀌는 장점도 있습니다.” |
| System Prompt에 Tool 규칙 없음 | “Tool을 `.defaultTools()`로 등록만 하고 System Prompt에 사용 규칙이 없으면, LLM이 Tool을 호출하지 않고 상상으로 답할 때가 있습니다. `[Tool 사용 규칙]` 섹션을 추가해서 ’반드시 Tool로만 조회, 추측 금지’를 명시하고, 각 Tool별 사용 맥락도 한 줄씩 적어보세요.” |
| Tool 메서드에 로그 없음 | “Tool 진입 로그가 없어서 ’이번 호출에서 LLM이 Tool을 불렀는지’를 확인할 수 없어요. 3단계 description 실험도 이 로그 없이는 비교가 불가능합니다. `log.info(\"[Tool] cancelOrder(...)\")` 한 줄을 추가하면 감사 로그 + 실험 도구가 됩니다.” |
| ToolParam description 없음/모호 | “`@ToolParam`이 빠져 있거나 `\"id\"` 한 단어로만 있어요. LLM이 사용자 입력에서 주문번호를 추출할 때 이 설명을 봅니다. `\"조회할 주문번호. 예: 2024-1234\"`처럼 **형식 힌트**를 넣으면 추출 정확도가 올라갑니다.” |
| description 실험 기록이 없음 | “3단계 description 실험을 하셨는데 수치 비교 표가 없어요. ‘버전 B에서 5회 중 2회만 Tool이 호출됨’ 같은 정량 기록이 있어야 ‘이 Tool을 왜 이렇게 설명했는지’ 근거가 됩니다. A/B/C 세 버전의 호출 횟수를 표로 정리해보세요.” |
| 설계 결정 문서 없음 | “코드는 잘 되어 있습니다. 이번 과제는 ‘왜 이렇게 했는가’를 README에 기록하는 것이 평가의 절반을 차지해요. ’View DTO에서 뺀 필드’, ‘Outcome 4개인 이유’, ‘description 언어 선택 기준’ 세 가지 설계 결정을 README에 적어주세요.” |
| 멱등성 제거 실험 미수행 | “2단계에서 `ALREADY_CANCELED` 분기를 실제로 제거하고 관찰해야 합니다. ’고객 오해 3가지 + 프로덕션 장애 3가지’를 상상으로만 쓰지 말고, 실제로 제거한 뒤 `canceledReason`이 어떻게 덮어씌워지는지 로그로 캡처해보세요. 체감하는 것과 상상하는 것은 다릅니다.” |
| AI 코드 리뷰가 피상적 | “‘에러 처리가 없다’는 좋은 지적이에요. 한 발 더 나가서, ’예외를 던지면 LLM이 Fallback을 못한다. 수업에서 배운 Outcome enum으로 바꾸면 LLM이 고객에게 `NOT_CANCELABLE`을 보고 상담원 연결을 안내할 수 있다’ 수준으로 **이번 주에 배운 해결책**과 연결해보세요.” |
| Tool 호출률이 들쭉날쭉한데 *“잘 됩니다”* 로 결론 | “시나리오 5종에서 호출률이 2/5 ~ 4/5로 변동했네요. 이 상태에서 *Outcome 4분기 / 멱등성 ablation*은 어떻게 결정적으로 검증하셨나요? LLM 호출 채널과 별개로 `POST /api/v1/admin/orders/{id}/cancel` 같은 admin endpoint로 Tool 메서드를 직접 호출하는 결정적 검증 채널을 분리하면, 비즈니스 로직 검증과 LLM 호출률 측정을 분리할 수 있습니다.” |
| `ChatClient` 빌더가 `@PostMapping` 메서드 안에 있음 | “`builder.defaultSystem(...).defaultTools(...).build()` 가 매 요청마다 실행되고 있어요. `ChatClient.Builder`는 stateful이라 `.defaultTools()`가 매번 누적되어 시간이 지나면 입력 토큰이 폭증합니다. 1주차에 같은 함정을 다뤘던 자리예요. 생성자로 옮겨서 단 한 번만 build하도록 바꿔주세요.” |
| Round 1 prompt 구조를 Round 2에서 그대로 사용 | “Round 1의 `SYSTEM_PROMPT`/`STREAMING_PROMPT` 분리를 그대로 두셨네요. 이 결정 자체는 괜찮지만, Round 2의 `[Tool 사용 규칙]` 섹션이 어느 prompt에 들어갔는지 / 두 prompt가 Tool 호출률에 어떻게 다른 영향을 주는지 한 번 측정해보셨는지 궁금합니다. 다른 PR에서는 *통합 후 호출률이 60%→80%로 올랐다* 라는 회고도 보였어요.” |
| description C가 *A보다 호출률이 높게* 나옴 | “흥미로운 발견이에요. 강의 자료는 *오해 description → 잘못된 Tool 선택*을 가정했는데, 측정에서는 C가 A와 같거나 더 높게 나왔네요. 가설은 무엇인가요? 메서드 이름의 키워드(`getDeliveryStatus`의 *delivery*)가 description을 보강한 것은 아닐지 — *호출은 됐지만 응답 품질은 깨졌는가*까지 비교해보면 더 깊은 결론이 나옵니다.” |
| 멱등성 분기 제거 실험에서 *덮어쓰임 대신 NOT_CANCELABLE* 만 관찰 | “분기 제거 후 `canceledReason` 덮어쓰임을 기대했는데 `NOT_CANCELABLE`로 빠지셨군요. `isCancelable()` 가드가 살아있어서 그렇습니다. 이 경로도 합격이지만 *둘 다 보고 싶다*면 `isCancelable()` 가드까지 함께 제거(Stage B) 한 뒤 같은 주문 2회 호출해보세요. *진짜 덮어쓰임*은 그때 잡힙니다. 가드가 가려준 사고를 일부러 들춰내는 실험이에요.” |
| `@ToolParam` 예시에 구체적 사유 박음 | “`@ToolParam(description = \"... 예: '집 앞에 사람이 없어요'\")` 처럼 구체적 예시 문자열을 넣으면, 사유 없는 발화에서 LLM이 *그 예시를 그대로 박아* 호출하는 패턴이 관찰됩니다. 호출률 지표는 90%인데 데이터 품질은 *전부 동일 가짜 사유*. 예시는 *추상적 형식*(`'고객이 말한 사유. 없으면 빈 문자열'`)으로 바꿔보세요.” |

---

## 추가 고려사항 — 프로덕션 확장 힌트

2주차 범위는 아니지만, 수강생이 다음을 **선제적으로 고려**하고 있다면 심화 점수를 부여하세요:

### 1) Tool 테스트 전략

```java
@Test
void cancelOrder_이미_취소된_주문은_ALREADY_CANCELED() {
    // given
    Order canceled = /* CANCELED 상태 주문 */;
    when(orderService.findById("2024-1238")).thenReturn(Optional.of(canceled));

    // when
    CancelOrderResult result = orderTools.cancelOrder("2024-1238", "재취소");

    // then
    assertThat(result.outcome()).isEqualTo(Outcome.ALREADY_CANCELED);
    verify(orderService, never()).save(any());  // 덮어쓰기 없음 검증
}
```

Tool은 LLM 없이 **순수 Java 단위 테스트로 검증 가능**해야 합니다. `OrderMockService`를 Mockito로 mock하면 판단/실행 분리의 설계 이점이 테스트에서도 드러납니다.

### 2) Circuit Breaker + 타임아웃

Tool이 실제 외부 서비스(주문 조회 API, 결제 취소 API 등)를 호출할 때는:

- **타임아웃**: 3~5초 이내. Tool이 느려지면 LLM 응답 전체가 느려짐
- **Circuit Breaker**: Resilience4j로 실패율이 높을 때 차단, `Outcome.SERVICE_UNAVAILABLE` 같은 신규 Outcome 반환
- **재시도 정책**: 조회(idempotent) Tool만 재시도 허용. 변경 Tool(cancelOrder)은 멱등성이 보장되어야 재시도 가능

이번 주차에서는 “Mock이라 해당 없음”이지만, 5주차 Guardrail에서 “Tool 실패 시 에스컬레이션” 주제로 다시 등장합니다.

### 3) Tool 권한 검증

현재 `cancelOrder`는 **누구나** 주문번호만 알면 취소 가능한 상태입니다. 프로덕션에서는:

- 세션/인증 컨텍스트에서 사용자 ID 확인
- `order.customerId()`와 일치하는지 검증
- 불일치 시 `Outcome.UNAUTHORIZED` 반환

3주차 Chat Memory와 함께 “세션에 묶인 사용자 정보”로 자연스럽게 확장됩니다.

### 4) Tool 호출 비용/로깅 확장

`[Tool] cancelOrder(orderId=...)` 정도의 로그는 최소한이고, 프로덕션에서는:

- 감사 테이블에 (userId, toolName, params, outcome, elapsedMs) 영속화
- Prometheus 메트릭: `tool_calls_total{name="cancelOrder", outcome="ALREADY_CANCELED"}`
- OpenTelemetry 트레이스로 LLM 호출 ↔︎ Tool 호출 ↔︎ DB 호출을 한 Span으로 연결

`PerformanceLoggingAdvisor`가 이 방향의 첫걸음입니다.

---

## AI가 생성한 Tool 코드를 리뷰할 때 확인할 포인트

수강생이 4단계(AI 코드 리뷰)에서 AI 생성 Tool 코드를 분석할 때, 다음 관점을 확인하세요.

### AI Tool 코드에서 흔히 발견되는 프로덕션 부적합 포인트

| 문제 유형 | 구체적 증상 | 이번 주 배운 해결책 |
| --- | --- | --- |
| **멱등성 부재** | `cancelOrder`가 이미 취소된 주문에 대한 분기 없이 그대로 `order.setStatus(CANCELED)` | `if (status == CANCELED) return ALREADY_CANCELED` 분기 |
| **예외 throw** | `throw new OrderNotFoundException()` / `throw new IllegalStateException()` | `Outcome.NOT_FOUND` / `Outcome.NOT_CANCELABLE` enum |
| **내부 엔티티 노출** | `@Tool` 메서드가 `Order` 엔티티 또는 JPA Entity 그대로 반환 | 전용 View DTO(`OrderDetailView`)로 변환 |
| **권한 검증 부재** | 주문번호만 있으면 누구나 취소 가능 | 세션 기반 사용자 확인 (3주차 확장) |
| **description 부실** | 영어 한 줄 또는 함수명을 단순 번역한 수준 | 무엇/언제/입력/실패 4요소 포함 한국어 description |
| **감사 로그 부재** | Tool 호출 이력 추적 불가 | `log.info("[Tool] ...")` 진입 시점 로깅 |
| **Outcome이 boolean** | `cancelOrder`가 `boolean` 또는 `void` 반환 | 4분기 Outcome enum |
| **Mock/DB가 Tool에 섞임** | Tool 클래스 안에 `Map<String, Order>` 또는 JPA Repository 직접 사용 | Service 계층 분리 |

### 리뷰 시 기대하는 수준

수강생이 AI 코드에서 위 문제 중 **3가지 이상**을 식별하고, 각각에 대해 **“이번 주에 배운 방식으로 어떻게 고칠지”**를 구체적으로 기술하면 합격입니다. 단순히 “멱등성이 없다”가 아니라, “`if (order.status() == CANCELED) return new CancelOrderResult(..., ALREADY_CANCELED, ...)` 분기를 추가하여 두 번째 호출에서도 `order.cancel()`이 재실행되지 않게 해야 한다” 수준의 구체성을 요구합니다.

### 합격선의 *세 가지 깊이* — 구체성으로 만점 가르기

같은 *“결함 3개 식별”* 이라도 PR마다 깊이가 갈립니다. 다음 3개 축이 모두 갖춰진 PR이 만점:

**1. AI 원본 코드를 *raw로 보존* 했는가**

❌ 부족 답: *“AI가 만든 코드는 멱등성이 없었다 (요약)”*
✅ 만점 답: AI에게 던진 *프롬프트 전문* + 받은 *코드 원본* 을 `docs/ai-original.md` 같은 별도 파일로 보존. 누구나 같은 프롬프트로 재현해서 차이를 볼 수 있어야 합니다.

이게 중요한 이유: AI 출력은 모델·시점·temperature에 따라 변합니다. *원본을 보존하지 않으면* 결함 분석의 근거가 사라집니다.

**2. 결함을 *본인 코드와 1:1 매핑* 했는가**

❌ 부족 답: *“결함 1: 멱등성 없음. 해결: ALREADY_CANCELED 분기 추가.”*
✅ 만점 답:

```markdown
| AI 코드 결함 | 위치 | 본인 코드 개선 위치 | 어떻게 |
|---|---|---|---|
| 멱등성 없음 | AI원본 line 23 `order.setStatus(CANCELED)` | `OrderTools.java` line 87-91 | `if (order.status() == CANCELED) return ALREADY_CANCELED;` 분기 추가 |
| 예외 throw | AI원본 line 18 `orElseThrow()` | `OrderTools.java` line 80-84 | `Outcome.NOT_FOUND` 값 반환으로 변경 |
| boolean 반환 | AI원본 line 30 `return true;` | `CancelOrderResult.java` 신규 | `record` + `Outcome` enum 도입 |
```

**3. 결함이 *왜 위험한지* 운영 시나리오로 설명했는가**

❌ 부족 답: *“멱등성이 없으면 안전하지 않다.”*
✅ 만점 답: *“`canceledReason`이 두 번째 호출 사유로 덮어쓰임 → 분쟁 시점에 왜 취소했는지 가 사라짐. 게다가 결제 환불 API와 연동되어 있다면 두 번째 호출에서 다시 환불 트리거 → 이중 환불.”*

> **리뷰 코멘트 템플릿**:
*“AI 원본 코드와 본인 개선 코드의 위치를 1:1로 짚어주시면 결함 분석의 신뢰도가 올라갑니다. 표 형식으로 AI line N → 본인 line M → 무엇을 바꿨는지 매핑해보세요. 그리고 각 결함이 프로덕션의 어떤 사고로 이어지는지 한 줄씩 덧붙이면 만점입니다.”*
> 

---

## 주차별 맥락 참고

2주차 리뷰에서는 아래 항목은 **아직 다루지 않으므로** 감점하지 않습니다:

- 대화 메모리 / 세션 관리 — 3주차 (`"그거 취소해주세요"` 같은 지시 대명사)
- 실제 RDB / JPA — 6주차 확장 시 고려
- RAG / VectorStore — 4주차
- Guardrail / 입력 검증 / Tool 실패 에스컬레이션 — 5주차
- 동시성 / 트랜잭션 / Tool 병렬 호출 — 6주차 및 이후

다만, 수강생이 이런 부분을 선제적으로 고려하고 있다면(예: “cancelOrder에 세션 기반 권한 체크가 필요한데 3주차 Memory 붙을 때 추가해야 할 것 같다”) 심화 점수로 인정합니다.

**2주차의 고유 포커스**는 다음 세 가지입니다. 이 축을 벗어나는 리뷰는 피하세요:

1. **Tool description 품질** — LLM이 이 문서만 보고 Tool을 쓸 수 있는가
2. **판단/실행 분리** — LLM은 판단만, 실행은 Spring Bean에
3. **멱등성** — 같은 요청 2회에 대해 시스템이 **같은 결과**를 주는가