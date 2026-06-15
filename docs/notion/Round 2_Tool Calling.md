# Round 2 — Tool Calling으로 주문/배달 시스템 연동

> 📝
이 페이지는 Round 2의 **개요와 강의 본문**을 한 곳에 모은 문서입니다.
> 

---

## 이번 라운드에 배우는 것

Round 1에 만든 "잘 답하는 챗봇"에 **실제 주문 데이터를 조회/변경하는 능력**을 붙입니다. 이 순간부터 우리가 만드는 것은 챗봇이 아닌 **에이전트**입니다.

- `@Tool` / `@ToolParam` 으로 주문 조회·배달 추적·주문 취소 메서드를 LLM이 호출 가능한 Tool로 등록한다
- **판단(LLM) / 실행(Spring Bean)** 의 경계선을 직접 그어본다
- 취소·결제처럼 위험한 Tool에 **멱등성(idempotency)** 을 설계한다
- Tool 호출 시 LLM과 서버가 **몇 번 왕복**하는지 로그로 관찰한다

> 🎯
**이번 라운드의 한 줄 메시지**: **"판단은 LLM, 실행은 Spring Bean."**
Tool을 만드는 것보다 **Tool의 경계(boundary)를 설계하는 훈련**이 핵심입니다.
> 

---

## 학습 목표

이번 라운드가 끝나면 다음을 할 수 있습니다.

- [ ]  **판단과 실행의 경계**를 자기 언어로 설명할 수 있다 — 어디까지가 LLM의 일이고, 어디서부터가 Spring Bean의 일인가
- [ ]  `@Tool` / `@ToolParam` 으로 주문 조회·배달 추적·주문 취소 메서드를 Tool로 등록할 수 있다
- [ ]  **`description`이 LLM에게 보여주는 유일한 API 문서**라는 사실을 체감하고, 4요소(무엇/언제/입력/실패)를 적용할 수 있다
- [ ]  Tool 호출 시 LLM과 서버가 몇 번 왕복하는지 로그로 관찰하고 설명할 수 있다
- [ ]  위험한 Tool(취소·결제)에 **멱등성**을 설계할 수 있고, 그 이유를 동료에게 설명할 수 있다
- [ ]  Tool이 null/에러/부분 실패를 돌려줄 때 에이전트가 **Fallback 응답**을 내도록 설계할 수 있다
- [ ]  내부 도메인 엔티티를 그대로 Tool 응답으로 노출하면 안 되는 이유를 설명할 수 있다 (보안 + 토큰 경제성)

---

## 사전 준비 체크리스트

> ⚠️
Round 1 프로젝트가 동작하지 않으면 Round 2은 시작할 수 없습니다. 빠르게 Round 1 을 마무리 후에 Round 2를 진행해주세요. 코드만 작성하는 것은 오래 걸리지 않아요 🥲
> 
- [ ]  **Round 1 숙제 완료** — System Prompt, Structured Output, `PerformanceLoggingAdvisor`가 동작하는 상태
- [ ]  JDK 17+ (`java -version`)
- [ ]  Ollama 실행 중, `qwen2.5` 다운로드 완료 (`ollama list`로 확인)
- [ ]  Postman / `httpie` / `curl` 중 편한 도구
- [ ]  (권장) `jq` — JSON 응답을 터미널에서 읽기 편합니다
- [ ]  (권장) IntelliJ IDEA — DEBUG 로그에서 `ToolResponseMessage`를 검색할 일이 많습니다

```bash
# 사전 검증
ollama list                # qwen2.5 보이는지
ollama run qwen2.5 "안녕"   # 응답 확인 후 Ctrl+D
```

---

## 1부. Tool Calling의 원리 — 판단은 LLM, 실행은 서버

### 1.1 에이전트가 "시스템"이 되는 순간

Round 1에서 우리는 "잘 답하는 상담원"을 만들었습니다. 그런데 다음 질문에는 답할 수 없습니다.

> "주문번호 2024-1234 지금 어디쯤 있어요?"
> 

LLM은 배달 주소를 모릅니다. 라이더 위치는 더더욱 모릅니다. 이 질문에 답하려면 우리 시스템에서 **현재 데이터**를 가져와야 합니다.

여기서 Tool Calling이 등장합니다.

> 🎯
**핵심 메시지**: LLM은 "어떤 Tool을 어떤 인자로 호출해야 하는지"만 결정한다. 실제 호출과 실행은 **우리 서버가 한다**. 결과는 다시 LLM에게 전달되어 자연어 응답으로 변환된다.
> 

### 1.2 왜 이게 중요한가

판단과 실행의 경계를 명확히 그으면 다음이 가능해집니다.

| 분리 이유 | 실제 의미 |
| --- | --- |
| **보안** | LLM이 DB에 직접 쿼리하지 않는다. Tool 호출만 할 수 있다. |
| **테스트** | Tool은 평범한 Spring Bean이다. JUnit으로 단위 테스트가 가능하다. |
| **감사(Audit)** | 모든 시스템 호출이 Tool 단위로 로깅된다. LLM이 무엇을 시도했는지 추적 가능하다. |
| **Fallback** | Tool이 예외를 던지면 LLM이 자연어로 Fallback할 수 있다. 반대로 LLM이 이상해도 Tool은 멀쩡하다. |
| **멱등성 제어** | 취소/환불 등 위험한 연산은 Tool 계층에서 한 번만 실행되도록 강제할 수 있다. |

### 1.3 Tool Calling의 실제 흐름

사용자가 `"주문번호 2024-1234 어디쯤 있어요?"`라고 물으면 이런 일이 일어납니다.

```
[1] 사용자 메시지 → ChatClient
[2] LLM에게 System Prompt + User Message + [사용 가능한 Tool 목록] 전달
[3] LLM 응답: "getDeliveryStatus(orderId='2024-1234')를 호출해줘"  ← 텍스트가 아님
[4] Spring AI가 OrderTools.getDeliveryStatus("2024-1234") 실행
[5] 실행 결과(DeliveryStatusView)를 LLM에게 다시 전달
[6] LLM이 결과를 자연어로 변환: "라이더가 역삼역 사거리 부근에 있으며 약 15분 후 도착 예정입니다."
[7] ChatClient가 최종 응답을 사용자에게 반환
```

> 💡
[2]~[6]은 **한 번의 `.call()` 안에서 모두 일어납니다**. 사용자 입장에서는 한 번의 API 호출이지만, 내부적으로 LLM과 서버가 여러 번 왕복합니다. `PerformanceLoggingAdvisor`가 측정하는 시간은 **Tool 실행을 포함한 전체 왕복 시간**입니다.
> 

### 1.4 Spring AI의 Tool 모델

| 구성 요소 | 역할 | 비유 |
| --- | --- | --- |
| `@Tool` | 메서드를 Tool로 노출 | `@PostMapping` — 외부에서 호출 가능한 엔드포인트 |
| `@Tool(name=...)` | Tool 식별자 — 미지정 시 메서드명 사용, 중복 시 등록 실패 | `@RequestMapping(name=...)` |
| `@ToolParam` | 파라미터에 자연어 설명 부여 | `@RequestParam(description=...)` |
| `description` | **LLM이 읽는 문서** — 이것만 보고 호출 여부를 결정한다 | OpenAPI summary |
| `.defaultTools(...)` | ChatClient에 Tool을 등록 | 라우터에 컨트롤러 등록 |

> ⚠️
**흔한 실수 — Tool name 충돌**
Spring AI는 ChatClient에 등록되는 Tool 이름이 **유일**해야 합니다. `@Tool`에 `name`을 지정하지 않으면 메서드명이 그대로 식별자가 되어, 다음 상황에서 충돌합니다.
> 
> - 여러 Tool 컴포넌트에 동일 메서드명이 있는 경우 (`OrderTools.getStatus`, `DeliveryTools.getStatus`)
> - 한 클래스에 메서드 오버로드가 있는 경우
> 
> 충돌 시 `Multiple tools with the same name 'xxx'` 에러로 빈 등록 자체가 실패합니다.
> 해결책:
> 
> 1. `@Tool(name = "getOrderStatus", description = "...")` 처럼 명시적 지정
> 2. **`<동사><도메인>` 컨벤션**으로 메서드명 통일: `getOrderStatus`, `trackDelivery`, `cancelOrder`

### `description`이 왜 결정적인가

`description`은 코드를 읽는 사람을 위한 주석이 아니라 **LLM이 호출 결정을 내릴 때 보는 사용 설명서**입니다. LLM은 메서드 시그니처를 실행해 보지 않습니다. **오직 `description`만 읽고** 사용자의 발화에 어느 Tool이 적합한지, 어떤 인자를 채울지를 결정합니다.

| 부실한 description의 결과 | 증상 |
| --- | --- |
| 무엇을 하는지 모호 | Tool을 호출해야 할 때 호출하지 않음 (recall 저하) |
| 호출 조건이 없음 | 엉뚱한 때 호출함 (precision 저하 — 인사말에도 주문 조회) |
| 파라미터 의미가 모호 | 주문번호 자리에 메뉴명을 채우는 식의 인자 오작성 |
| 반환 형태가 없음 | LLM이 응답을 어떻게 가공할지 몰라 횡설수설 |
| 실패/예외 케이스 없음 | null 반환 시에도 "확인했습니다" 같은 거짓 응답 |

**좋은 description의 4요소:**

1. **무엇을 하는가** (한 문장 동작 요약)
2. **언제 호출해야 하는가** (사용자가 이런 의도를 보일 때)
3. **언제 호출하지 말아야 하는가** (호출 조건의 부정형)
4. **반환되는 값의 의미** (주요 필드와 null/빈 값의 의미)

> 💡
description은 **한국어로** 작성하세요. LLM이 한국어 사용자의 발화와 매칭하는 거리가 짧아져 호출 정확도가 올라갑니다. 영어 한 줄짜리 `"Get order info"`는 거의 항상 실패합니다.
> 

---

## 2부. `@Tool` 설계 — description, 시그니처, 반환 타입

### 2.1 Mock 도메인 설계

실제 서비스라면 `OrderRepository` + JPA일 자리를 교육용으로 `Map` 기반 Mock으로 대체합니다. Round 1와 달리 **상태를 바꾸는 기능(cancel)** 이 들어오므로 record가 아닌 가변 클래스로 둡니다.

```java
public class Order {
    private final String orderId;
    private final String storeName;
    private final List<OrderItem> items;
    private final int totalAmount;
    private final LocalDateTime orderedAt;
    private final LocalDateTime estimatedDeliveryAt;
    private final String deliveryAddress;
    private final String riderLocation;

    private OrderStatus status;
    private String canceledReason;
    private LocalDateTime canceledAt;

    public void cancel(String reason, LocalDateTime at) {
        this.status = OrderStatus.CANCELED;
        this.canceledReason = reason;
        this.canceledAt = at;
    }

    public boolean isCancelable() {
        return status == OrderStatus.CREATED || status == OrderStatus.ACCEPTED;
    }
}

@Service
public class OrderMockService {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        // 2024-1234 (DELIVERING), 2024-1235 (CREATED), 2024-1236 (DELIVERED),
        // 2024-1237 (COOKING — 취소 불가), 2024-1238 (이미 CANCELED — 멱등 테스트용),
        // 2024-1239 (ACCEPTED — 취소 가능)
    }

    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
```

> 💡
**왜 H2/JPA를 안 쓰는가?** Round 2 목표는 "Tool Calling 흐름의 이해"입니다. DB 세팅은 수강생의 주의를 분산시킵니다. 실제 서비스라면 `OrderMockService`가 `OrderRepository`를 주입받는 `OrderService`로 바뀔 뿐, Tool의 시그니처는 동일합니다.
> 

### 2.2 Tool 반환 DTO를 분리하는 이유

`@Tool` 메서드가 내부 도메인 모델(`Order`)을 그대로 반환하면 두 문제가 생깁니다.

1. **민감 정보 노출** — 라이더 전화번호, 내부 좌표, 취소 이력 등이 LLM에게 그대로 전달됨
2. **입력 토큰 폭증** — 불필요한 필드까지 JSON 직렬화되어 LLM 입력에 들어감

그래서 Tool 응답 전용 View DTO를 만듭니다.

```java
public record OrderDetailView(
        String orderId,
        String storeName,
        List<Line> items,
        int totalAmount,
        String status,
        LocalDateTime orderedAt,
        LocalDateTime estimatedDeliveryAt
) {
    public record Line(String menuName, int quantity, int unitPrice) {}
}
```

> 🎯
Tool의 반환 타입은 **LLM이 읽을 수 있도록 정돈된 자연어 키**를 가진 DTO여야 한다. 내부 엔티티를 그대로 노출하지 말 것.
> 

### 2.3 `OrderTools` — @Tool 세 개

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
            주어진 주문번호의 현재 배달 상태와 라이더 위치를 조회한다.
            배달 중인 주문에 대해서만 라이더 위치가 반환되며,
            아직 배달이 시작되지 않았거나 이미 배달 완료된 주문은 상태만 반환된다.
            존재하지 않는 주문번호면 null을 반환한다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "배달 상태를 조회할 주문번호. 예: 2024-1234") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);
        return orderService.findById(orderId).map(this::toDeliveryView).orElse(null);
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
        // 본문은 3부에서 자세히
    }
}
```

### 2.4 Tool description 작성 규칙

`description`이 곧 LLM에게 보여주는 API 문서입니다. 아래 네 가지가 반드시 들어가야 합니다.

| 항목 | 예시 |
| --- | --- |
| **무엇을 하는가** | "주어진 주문번호의 상세 정보를 조회한다" |
| **언제 호출해야 하는가** | "고객이 메뉴, 금액, 상태를 물을 때" |
| **입력 형식** | `"YYYY-XXXX 형식이며 (예: 2024-1234)"` |
| **실패 시 반환값** | `"존재하지 않으면 null을 반환한다"` |

### 2.4.1 Q&A — System Prompt와 description, LLM은 어느 쪽을 보고 판단하나?

> **Q.** System Prompt에도 "이런 발화에는 주문 조회 Tool을 써라"고 적었고, `@Tool description`에도 비슷한 내용을 적었습니다. LLM은 둘 중 어느 걸 보고 판단하나요?
> 

**둘 다 봅니다. 단, 역할이 다릅니다.** LLM은 매 호출마다 다음 묶음을 한 덩어리로 받습니다.

```
[System Prompt]   ← 페르소나·정책·언제 Tool을 써야 하는지에 대한 큰 그림
[Tool 1: name + description + parameters schema]   ← Tool 카탈로그
[Tool 2: ...]
[User Message]    ← 사용자 발화
```

그리고 **두 단계 판단**을 거치는데, 각 단계에서 보는 텍스트가 다릅니다.

**1단계 — "Tool을 써야 하는 상황인가?"**: 주로 **System Prompt**의 정책 문장을 본다.

```
[Tool 사용 규칙]
- 주문번호(YYYY-XXXX 형식)가 발화에 포함되면 반드시 주문 관련 Tool을 호출한다.
- 일반 인사("안녕", "고마워")에는 Tool을 호출하지 않는다.
- 정책 질문("환불 가능해요?")에는 Tool 없이 정책 텍스트로만 답한다.
```

**2단계 — "어느 Tool을 쓸까? 인자는 뭘 채울까?"**: 주로 각 Tool의 **`name` + `description` + 파라미터 schema**를 본다.

| 어디에 적나 | 무엇을 적나 |
| --- | --- |
| **System Prompt** | **언제 Tool 호출이 필요/불필요한지** 정책, 페르소나, 응답 톤, 보안 규칙 |
| **`@Tool description`** | **이 Tool 자체가** 무엇을 하는지, 입력/출력의 의미, 호출 조건 |

**충돌하면 누가 이기나** — 일반적으로 **System Prompt가 우세**합니다. 모델 입장에서 System은 "운영자가 정한 절대 규칙", description은 "API 명세서" 같은 위계로 학습되어 있기 때문입니다.

단, **모델이 작거나(예: 7B 미만) System Prompt가 모호하면** 이 우선순위가 무너지고 description으로 끌려가는 일이 자주 일어납니다.

**디버깅 체크리스트**

| 증상 | 먼저 의심할 곳 |
| --- | --- |
| 호출돼야 할 때 호출 안 됨 (false negative) | **System Prompt** — `[Tool 사용 규칙]`이 모호하지 않은가? |
| 엉뚱한 Tool이 호출됨 | **description** — 두 Tool의 description이 너무 비슷하지 않은가? |
| 인자가 잘못 채워짐 | **`@ToolParam(description=...)` + 파라미터 schema** |
| 인사말에도 Tool이 호출됨 (false positive) | **System Prompt의 호출 금지 조건** + description의 부정 조건 |

> 🎯
**한 줄 요약System Prompt = "Tool을 쓸지 말지" 결정. description = "어떤 Tool을 쓸지" 결정.**
두 곳에 같은 내용을 중복으로 적지 말고, **정책은 System Prompt에 / 기능 설명은 description에** 분리해서 작성하라.
> 

### 2.4.2 Q&A — System Prompt에 "Tool 써라" 안 써도 호출되던데요?

> **Q.** 제 System Prompt에는 `[Tool 사용 규칙]` 같은 게 없습니다. 그냥 페르소나·금지사항만 적었어요. 그런데 `"2024-1234 지금 어디쯤 있어요?"`라고 물으면 Tool이 호출됩니다. 어떻게?
> 

**현실은 이렇습니다.** 앞 절에서 정리한 "1단계 = System Prompt, 2단계 = description"은 **잘 설계된 시스템에서 그래야 한다는 당위**입니다. 실제 LLM은 두 단계를 **하나의 forward pass에서 동시에** 평가하며, 어느 신호가 더 강한지에 따라 결정이 갈립니다.

| 신호 출처 | 강도 | 설명 |
| --- | --- | --- |
| **모델의 사전학습 행동** | ✅ 강함 | qwen2.5 같은 instruction-tuned 모델은 "사용자가 데이터 조회를 요청하고 매칭되는 Tool이 등록돼 있으면 호출한다"는 패턴을 이미 학습한 상태 |
| **Tool description ↔ 발화 의미 매칭** | ✅ 강함 | "어디쯤 있어요" ↔ "라이더 위치 조회"가 강하게 매칭 |
| **System Prompt의 호출 정책** | ⚠️ 없음 | 비어 있으면 중립 (막지도, 권하지도 않음) |

→ **사전학습된 행동 + description의 강한 매칭**이 합쳐져 System Prompt가 침묵해도 호출이 발생한 것.

**그러면 안 써도 되나?** ❌ **위험합니다.** 정책이 없으면 다음 같은 상황에서 흔들립니다.

| 입력 | 위험 |
| --- | --- |
| `"안녕하세요"` | 그래도 Tool을 호출하려고 시도 (false positive) |
| `"환불 정책 알려줘"` | Tool 없는 정책 응답이어야 하는데 없는 Tool 시도 |
| `"주문 취소해줘"` (주문번호 없이) | `orderId`를 멋대로 지어내거나 hallucinate |
| `"라이더 전화번호 알려줘"` | 조회 후 개인정보 노출 |
| 작은 모델로 교체 시 | 위 위험들이 폭증 |

### 2.5 SupportController에 Tool 등록

`ChatClient.Builder`로 Tool을 등록할 때는 **생성자에서 한 번만 빌드**해야 합니다. 매 요청마다 핸들러 안에서 `.defaultTools(...)`를 호출하면 같은 Tool이 누적 등록되어 두 번째 요청부터 깨집니다.

```java
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;   // ✅ Builder가 아니라 ChatClient를 보관

    public AssistantController(ChatClient.Builder builder,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .defaultTools(orderTools)
                .build();   // ✅ 생성자에서 한 번만 build()
    }

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        return chatClient.prompt()
                .user(req.message())
                .call()
                .content();
    }
}
```

> 💡
**`.tools()` vs `.defaultTools()`** — 전자는 해당 호출에만, 후자는 ChatClient에서 만들어지는 모든 요청에 적용. 컨트롤러 단위로 Tool을 고정하고 싶다면 `.defaultTools()`가 맞습니다.
> 

### 2.5.1 흔한 함정 — `ChatClient.Builder`는 싱글톤이다

핸들러 메서드 안에서 `.defaultTools(...)`를 호출하면 첫 요청은 동작하지만 **두 번째 요청부터 `IllegalStateException: Multiple tools with the same name (getOrderDetail1, ...) found`** 에러가 납니다.

```java
// ❌ 잘못된 패턴 — 매 요청마다 누적된다
@RestController
@RequiredArgsConstructor
public class AssistantController {

    private final ChatClient.Builder builder;  // 스프링이 주입한 싱글톤
    private final OrderTools orderTools;

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        return builder
                .defaultTools(orderTools)   // ❌ 매 호출마다 또 등록됨
                .build()
                .prompt().user(req.message()).call().content();
    }
}
```

**왜 그런가** — Spring AI 자동 설정이 등록하는 `ChatClient.Builder`는 **싱글톤** 빈입니다. `.defaultTools()`, `.defaultSystem()`, `.defaultAdvisors()`는 모두 빌더 내부 컬렉션에 **append**하는 동작입니다.

| 요청 횟수 | builder의 tool 목록 | 결과 |
| --- | --- | --- |
| 1회 | `[getOrderDetail, getDeliveryStatus, cancelOrder]` | ✅ 정상 |
| 2회 | `[getOrderDetail, getDeliveryStatus, cancelOrder, getOrderDetail, ...]` | ❌ 이름 중복 → `getOrderDetail1` suffix가 붙고 검증에서 예외 |

에러 메시지의 **숫자 suffix**(`getOrderDetail1`)가 결정적인 단서입니다.

**해결 패턴 두 가지:**

**(A) 컨트롤러 단위로 고정된 설정 → 생성자에서 한 번만 빌드** (위의 2.5 코드 예시)

**(B) 요청마다 system이나 tool이 달라지는 경우 → default 없이 호출별 메서드 사용**

```java
// PromptLabController처럼 systemPrompt가 매 요청 달라지는 경우
@PostMapping
public PromptLabResult experiment(@RequestBody PromptLabRequest req) {
    return chatClient.prompt()
            .system(req.systemPrompt())   // ✅ 호출별 system
            .user(req.message())
            .call()
            .entity(SupportResponse.class);
}
```

> 🎯
**원칙 한 줄 요약`ChatClient.Builder` 빈에 `.defaultXxx()`를 매 요청마다 호출하지 말 것.** 한 번만 빌드해서 `ChatClient`를 재사용하거나, 호출별 `.system()` / `.tools()` / `.advisors()`를 써라.
> 

### 2.6 라이브 데모 — 시나리오 3종

```bash
# 데모 1: 주문 상세 조회 → getOrderDetail 호출
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"2024-1234 어떤 메뉴 주문했는지 알려주세요"}'

# 데모 2: 배달 위치 조회 → getDeliveryStatus 호출
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"2024-1234 라이더가 어디쯤 있어요?"}'

# 데모 3: 존재하지 않는 주문 — Tool이 null 반환 시 LLM의 반응
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"2099-9999 배달 어디예요?"}'
```

**콘솔에서 함께 관찰:**

- `[Tool] getDeliveryStatus(orderId=2024-1234)` — 내 코드가 호출된 증거
- `LLM 호출 완료 — XXXms | 입력 토큰: YY | 출력 토큰: ZZ` — Tool 왕복 포함 전체 시간

> 🎯
**체크포인트**: `2099-9999`를 보냈을 때 LLM이 "확인했는데 없습니다" 비슷하게 답하면 설계 성공. "주문이 강남에서 배달 중입니다" 같은 거짓말을 하면 System Prompt의 `[Tool 사용 규칙]` 섹션을 더 강하게 써야 합니다.
> 

---

## 3부. 멱등성 설계 — cancelOrder의 함정

### 3.1 왜 멱등성을 Round 2에 다루는가

`getOrderDetail`이나 `getDeliveryStatus`는 안전합니다. 여러 번 호출해도 부작용이 없죠. 하지만 `cancelOrder`는 다릅니다.

고객이 이렇게 말합니다.

> "아 취소 부탁드려요. 아 진짜 취소 맞아요. 한 번 더 확인해주세요."
> 

LLM이 이 발화를 보고 `cancelOrder`를 **두 번 호출**할 수 있습니다. 혹은 네트워크 재시도, 클라이언트 중복 요청, LLM의 Tool 반복 호출 등 원인은 수없이 많습니다.

> 🎯
**핵심 메시지**: LLM 기반 시스템은 **동일 요청이 여러 번 오는 것이 정상**이다. 부작용을 일으키는 모든 Tool은 기본값으로 **멱등(idempotent)** 해야 한다.
> 

### 3.2 멱등성의 세 가지 수준

| 수준 | 동작 | 예시 |
| --- | --- | --- |
| **에러** | 두 번째 호출에서 예외 | "이미 취소된 주문입니다" 500 Error |
| **무시** | 두 번째 호출을 조용히 무시 | void 반환, 로그 없음 |
| **같은 응답 재전달** | 두 번째 호출도 성공으로 처리, 상태 정보 전달 | `ALREADY_CANCELED` 결과 반환 |

LLM 기반 에이전트에서는 **세 번째(같은 응답 재전달)** 가 정답입니다.

- **에러**를 주면 LLM이 당황. "취소 실패"라고 고객에게 전달할 위험
- **무시**하면 LLM이 상태를 모름. 방금 뭐가 일어났는지 설명 불가
- **같은 응답 + 상태 코드**면 LLM이 "이미 취소하신 상태이므로 다시 처리하지 않았습니다"라고 정확히 설명 가능

### 3.3 `CancelOrderResult`로 상태 표현하기

boolean 성공/실패는 정보가 너무 빈약합니다. enum으로 상태를 표현합니다.

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

> 💡
이 enum은 **LLM이 읽습니다**. 값이 자연어처럼 읽혀야 LLM이 의미를 파악합니다. `STATUS_1`, `CODE_42` 같은 이름은 절대 금지.
> 

### 3.4 멱등성 구현

```java
@Tool(description = "...")
public CancelOrderResult cancelOrder(String orderId, String reason) {
    Order order = orderService.findById(orderId).orElse(null);
    if (order == null) {
        return new CancelOrderResult(orderId, Outcome.NOT_FOUND,
                "해당 주문번호를 찾을 수 없습니다.");
    }

    // 멱등성: 이미 취소된 주문은 에러 없이 동일한 성공 응답을 돌려준다.
    if (order.status() == OrderStatus.CANCELED) {
        return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED,
                "해당 주문은 이미 취소된 상태입니다. (취소 사유: " + order.canceledReason() + ")");
    }

    if (!order.isCancelable()) {
        return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE,
                "조리가 이미 시작되어(" + order.status() + ") 자동 취소가 불가합니다. 상담원 연결이 필요합니다.");
    }

    order.cancel(reason, LocalDateTime.now());
    return new CancelOrderResult(orderId, Outcome.CANCELED,
            "주문이 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있습니다.");
}
```

### 3.5 라이브 데모 — 중복 취소 시나리오

```bash
# 1) 취소 가능한 주문을 한 번 취소
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"2024-1239 주문 취소해주세요. 집 앞에 아무도 없어요"}'

# 2) 같은 주문을 다시 취소 요청 → ALREADY_CANCELED
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"2024-1239 진짜 취소된 거 맞나요? 한 번 더 취소해주세요"}'

# 3) 이미 조리 중인 주문 취소 시도 → NOT_CANCELABLE
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"2024-1237 주문 취소해주세요"}'

# 4) 사전 취소된 주문(2024-1238) 조회
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"2024-1238 어떻게 됐어요?"}'
```

**관찰 포인트:**

| 시나리오 | 내부 Tool 결과 | 기대 자연어 응답 |
| --- | --- | --- |
| 첫 취소 | `CANCELED` | "취소되었습니다. 결제 취소는 최대 7영업일..." |
| 재취소 요청 | `ALREADY_CANCELED` | "이미 취소된 상태입니다" |
| 조리 중 취소 | `NOT_CANCELABLE` | "조리가 시작되어 자동 취소 불가, 상담원 연결" |
| 없는 주문 | `NOT_FOUND` | "주문번호를 찾을 수 없습니다" |

> 🎯
**체크포인트**: 2) 시나리오에서 LLM이 "취소되었습니다"를 두 번 말하지 않고 "이미 취소된 상태입니다"라고 답하면 멱등 설계가 LLM에게 잘 전달된 것입니다.
> 

### 3.6 실무에서 한 걸음 더

수업에서는 메모리 Map으로 충분하지만, 실제 서비스에서는 다음도 고려합니다.

- **Idempotency Key** 헤더 — 같은 요청 식별자로 5분 내 재시도 시 캐시된 응답 재전달
- **낙관적 락(Optimistic Lock)** — `@Version` 필드로 동시 취소 충돌 방지
- **SAGA/Outbox** — 결제 취소는 외부 PG 호출이 포함되므로 분리 트랜잭션 필요

지금은 "멱등성의 필요성을 체감"하는 것이 목표입니다. 구체적 패턴은 4~Round 5에서 실패 처리와 함께 다룹니다.

---

## 4부. Observability — Tool 왕복을 관찰하기

### 4.1 `PerformanceLoggingAdvisor`가 측정하는 것

Round 1에서 만든 `PerformanceLoggingAdvisor`는 Tool Calling이 추가되어도 그대로 동작합니다.

> 🎯
Tool 호출이 추가되면 Advisor가 측정하는 시간은 "LLM 단일 호출 시간"이 아니라 **Tool 실행을 포함한 전체 왕복 시간**입니다. Spring AI는 `.call()` 안에서 다음을 모두 끝냅니다.
> 
> 1. 1차 LLM 호출 (Tool 필요 여부 판단)
> 2. Tool 실행 (우리 서버 코드)
> 3. 2차 LLM 호출 (Tool 결과를 자연어로 변환)

### 4.2 로그에서 찾아야 할 것

`/api/v1/assistant`를 호출하면 콘솔에 다음이 순서대로 나타납니다.

| 순서 | 로그 | 의미 |
| --- | --- | --- |
| 1 | `[DEBUG] Prompt: [SystemMessage: ..., UserMessage: ...]` | LLM에게 전달된 초기 프롬프트 (Tool 정의 포함) |
| 2 | `[INFO] [Tool] getDeliveryStatus(orderId=...)` | 내 코드가 실행된 시점 |
| 3 | `[DEBUG] Prompt: [..., ToolResponseMessage: ...]` | Tool 결과가 LLM에게 재전달되는 2차 프롬프트 |
| 4 | `[INFO] LLM 호출 완료 — XXXms \| 입력 토큰: ... \| 출력 토큰: ...` | 최종 응답 완료 |

### 4.3 입력 토큰이 왜 갑자기 늘었는가

Tool Calling이 적용되면 Round 1 대비 입력 토큰이 상당히 늘어납니다.

- Spring AI가 Tool 정의(`description`, 파라미터, JSON 스키마)를 자동으로 프롬프트에 주입
- 2차 LLM 호출 시 Tool 실행 결과(JSON)가 통째로 프롬프트에 붙음

> 💡
**실무 관점**: Tool이 많아질수록 입력 토큰이 선형으로 증가합니다. 프로덕션에서는 (1) description을 간결하게, (2) 관련 없는 Tool은 엔드포인트별로 분리해 `.tools()`로 주입, (3) 반환 DTO를 최소화하는 방향으로 최적화합니다.
> 

### 4.4 라이브 데모 — 토큰 수 변화 관찰

```bash
# Round 1 방식 — Tool 없음
curl -s -X POST <http://localhost:8080/api/v1/chat> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"안녕하세요?"}'

# Round 2 방식 — Tool 등록됨 (실제 Tool 호출은 안 하더라도 description이 프롬프트에 포함됨)
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"안녕하세요?"}'
```

두 경우의 입력 토큰 수 차이가 곧 "Tool 정의의 비용"입니다.

---

## 다음 라운드 예고 — Round 3: 대화 맥락 관리와 메모리 설계

다음 시간에는 **대화가 여러 턴 이어지는 상황**을 다룹니다.

- 고객: "2024-1234 지금 어디예요?" → 봇: "라이더 이동 중..." → 고객: "아 그거 취소해주세요"
- 두 번째 발화에서 "그거"가 무엇인지 알려면 **Chat Memory**가 필요합니다.
- `Advisor`와 `ChatMemory`를 연결하고, `conversationId`로 고객별 세션을 분리합니다.
- 토큰 한도 대응 — 슬라이딩 윈도우 vs 대화 요약 전략.
- (선행 학습 권장) Spring AI의 `MessageChatMemoryAdvisor` 문서 한 번 훑어오기.