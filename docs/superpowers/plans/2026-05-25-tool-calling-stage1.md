# Tool Calling Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `OrderTools` 3개 + Mock 데이터 6건 + `AssistantController` 구현으로 5종 시나리오(주문 조회/배달 위치/취소/NOT_CANCELABLE/주문 없음)를 검증한다.

**Architecture:** 도메인 모델(`Order`)은 내부에만 존재하고, `@Tool` 메서드는 View DTO만 LLM에게 노출한다. 판단(어떤 Tool을 쓸지)은 LLM, 실행(상태 변경·비즈니스 규칙)은 Spring Bean. `cancelOrder`는 CANCELED/ALREADY_CANCELED/NOT_CANCELABLE/NOT_FOUND 4-outcome 멱등 설계.

**Tech Stack:** Spring AI 1.0 (`org.springframework.ai.tool.annotation.Tool`, `@ToolParam`), Spring Boot 3, Lombok, Jakarta Validation, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-05-24-tool-calling-boundary-design.md`

---

## 파일 맵

| 파일 | 역할 | 신규/수정 |
|---|---|---|
| `Order.java` | 가변 도메인 모델 — `cancel()`, `isCancelable()` | 신규 |
| `OrderStatus.java` | enum: CREATED/ACCEPTED/COOKING/DELIVERING/DELIVERED/CANCELED | 신규 |
| `OrderItem.java` | record: menuName, quantity, unitPrice | 신규 |
| `OrderMockService.java` | @Service, ConcurrentHashMap, @PostConstruct seed 6건 | 신규 |
| `OrderDetailView.java` | Tool 응답 DTO — deliveryAddress/riderLocation 제외 | 신규 |
| `DeliveryStatusView.java` | Tool 응답 DTO — riderLocation(nullable) 포함 | 신규 |
| `CancelOrderResult.java` | Tool 응답 record + Outcome enum | 신규 |
| `OrderTools.java` | @Component, @Tool 3개, 한국어 description | 신규 |
| `AssistantController.java` | /api/v1/assistant, 생성자 패턴 | 신규 |
| `BaedalPrompt.java` | [Tool 사용 규칙] 섹션 추가 | 수정 |
| `SupportController.java` | `.tools(orderTools)` 추가 (per-request 등록) | 수정 |
| `OrderTest.java` | isCancelable() / cancel() 단위 테스트 | 신규 |
| `OrderMockServiceTest.java` | seed 6건 / 2024-1238 사전 취소 검증 | 신규 |
| `OrderToolsTest.java` | 4-outcome 멱등성 포함 순수 단위 테스트 | 신규 |
| `AssistantControllerTest.java` | @WebMvcTest, Tool 등록 wiring 검증 | 신규 |
| `SupportControllerTest.java` | tools(orderTools) mock 추가 | 수정 |
| `OrderToolsMultilingualAccuracyTest.java` | @Tag("multilingual"), 실제 Ollama 필요 | 신규 |

---

## Task 1: 도메인 모델 — OrderStatus, OrderItem, Order

**Files:**
- Create: `src/main/java/com/baedal/support/OrderStatus.java`
- Create: `src/main/java/com/baedal/support/OrderItem.java`
- Create: `src/main/java/com/baedal/support/Order.java`
- Test: `src/test/java/com/baedal/support/OrderTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/baedal/support/OrderTest.java`를 생성한다.

```java
package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    private Order order(OrderStatus status) {
        return new Order("2024-0001", "테스트가게",
                List.of(new OrderItem("메뉴", 1, 10000)),
                10000, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30),
                "서울시 강남구 테헤란로 1", null, status);
    }

    @Test
    void isCancelable_returns_true_for_CREATED() {
        assertThat(order(OrderStatus.CREATED).isCancelable()).isTrue();
    }

    @Test
    void isCancelable_returns_true_for_ACCEPTED() {
        assertThat(order(OrderStatus.ACCEPTED).isCancelable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"COOKING", "DELIVERING", "DELIVERED", "CANCELED"})
    void isCancelable_returns_false_for_non_cancelable_statuses(OrderStatus status) {
        assertThat(order(status).isCancelable()).isFalse();
    }

    @Test
    void cancel_sets_status_to_CANCELED_and_records_reason_and_time() {
        Order o = order(OrderStatus.CREATED);
        LocalDateTime at = LocalDateTime.of(2026, 5, 25, 12, 0);

        o.cancel("배달 지연", at);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(o.getCanceledReason()).isEqualTo("배달 지연");
        assertThat(o.getCanceledAt()).isEqualTo(at);
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 에러로 실패하는지 확인**

```bash
./gradlew test --tests "com.baedal.support.OrderTest" 2>&1 | tail -10
```

예상: `error: cannot find symbol` (OrderStatus, OrderItem, Order 미존재)

- [ ] **Step 3: OrderStatus, OrderItem, Order 구현**

`src/main/java/com/baedal/support/OrderStatus.java`:
```java
package com.baedal.support;

public enum OrderStatus {
    CREATED, ACCEPTED, COOKING, DELIVERING, DELIVERED, CANCELED
}
```

`src/main/java/com/baedal/support/OrderItem.java`:
```java
package com.baedal.support;

public record OrderItem(String menuName, int quantity, int unitPrice) {}
```

`src/main/java/com/baedal/support/Order.java`:
```java
package com.baedal.support;

import java.time.LocalDateTime;
import java.util.List;

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

    public Order(String orderId, String storeName, List<OrderItem> items, int totalAmount,
                 LocalDateTime orderedAt, LocalDateTime estimatedDeliveryAt,
                 String deliveryAddress, String riderLocation, OrderStatus status) {
        this.orderId = orderId;
        this.storeName = storeName;
        this.items = items;
        this.totalAmount = totalAmount;
        this.orderedAt = orderedAt;
        this.estimatedDeliveryAt = estimatedDeliveryAt;
        this.deliveryAddress = deliveryAddress;
        this.riderLocation = riderLocation;
        this.status = status;
    }

    public void cancel(String reason, LocalDateTime at) {
        this.status = OrderStatus.CANCELED;
        this.canceledReason = reason;
        this.canceledAt = at;
    }

    public boolean isCancelable() {
        return status == OrderStatus.CREATED || status == OrderStatus.ACCEPTED;
    }

    public String getOrderId()                      { return orderId; }
    public String getStoreName()                    { return storeName; }
    public List<OrderItem> getItems()               { return items; }
    public int getTotalAmount()                     { return totalAmount; }
    public LocalDateTime getOrderedAt()             { return orderedAt; }
    public LocalDateTime getEstimatedDeliveryAt()   { return estimatedDeliveryAt; }
    public String getDeliveryAddress()              { return deliveryAddress; }
    public String getRiderLocation()                { return riderLocation; }
    public OrderStatus getStatus()                  { return status; }
    public String getCanceledReason()               { return canceledReason; }
    public LocalDateTime getCanceledAt()            { return canceledAt; }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.baedal.support.OrderTest" 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, `6 tests completed`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/baedal/support/OrderStatus.java \
        src/main/java/com/baedal/support/OrderItem.java \
        src/main/java/com/baedal/support/Order.java \
        src/test/java/com/baedal/support/OrderTest.java
git commit -m "feat: add Order domain model with isCancelable and cancel"
```

---

## Task 2: View DTO — OrderDetailView, DeliveryStatusView, CancelOrderResult

**Files:**
- Create: `src/main/java/com/baedal/support/OrderDetailView.java`
- Create: `src/main/java/com/baedal/support/DeliveryStatusView.java`
- Create: `src/main/java/com/baedal/support/CancelOrderResult.java`

View DTO는 순수 record 선언이므로 별도 단위 테스트가 없다. 경계 검증(어떤 필드가 없는가)은 Task 4의 `OrderToolsTest`에서 컴파일 타임에 확인된다.

- [ ] **Step 1: OrderDetailView 생성**

`src/main/java/com/baedal/support/OrderDetailView.java`:
```java
package com.baedal.support;

import java.time.LocalDateTime;
import java.util.List;

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

- [ ] **Step 2: DeliveryStatusView 생성**

`src/main/java/com/baedal/support/DeliveryStatusView.java`:
```java
package com.baedal.support;

import java.time.LocalDateTime;

public record DeliveryStatusView(
        String orderId,
        String status,
        String riderLocation,
        LocalDateTime estimatedDeliveryAt
) {}
```

- [ ] **Step 3: CancelOrderResult 생성**

`src/main/java/com/baedal/support/CancelOrderResult.java`:
```java
package com.baedal.support;

public record CancelOrderResult(
        String orderId,
        Outcome outcome,
        String message
) {
    public enum Outcome {
        CANCELED,
        ALREADY_CANCELED,
        NOT_CANCELABLE,
        NOT_FOUND
    }
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew compileJava 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/baedal/support/OrderDetailView.java \
        src/main/java/com/baedal/support/DeliveryStatusView.java \
        src/main/java/com/baedal/support/CancelOrderResult.java
git commit -m "feat: add View DTOs — OrderDetailView, DeliveryStatusView, CancelOrderResult"
```

---

## Task 3: OrderMockService — 6건 시드 데이터

**Files:**
- Create: `src/main/java/com/baedal/support/OrderMockService.java`
- Test: `src/test/java/com/baedal/support/OrderMockServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/baedal/support/OrderMockServiceTest.java`:
```java
package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMockServiceTest {

    private OrderMockService service;

    @BeforeEach
    void setUp() {
        service = new OrderMockService();
        service.seed();
    }

    @Test
    void seed_creates_all_6_orders() {
        assertThat(service.findById("2024-1234")).isPresent();
        assertThat(service.findById("2024-1235")).isPresent();
        assertThat(service.findById("2024-1236")).isPresent();
        assertThat(service.findById("2024-1237")).isPresent();
        assertThat(service.findById("2024-1238")).isPresent();
        assertThat(service.findById("2024-1239")).isPresent();
    }

    @Test
    void order_2024_1234_is_DELIVERING_with_riderLocation() {
        Order o = service.findById("2024-1234").orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DELIVERING);
        assertThat(o.getRiderLocation()).isEqualTo("역삼역 사거리");
    }

    @Test
    void order_2024_1238_is_pre_canceled_with_reason() {
        Order o = service.findById("2024-1238").orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(o.getCanceledReason()).isNotBlank();
        assertThat(o.getCanceledAt()).isNotNull();
    }

    @Test
    void findById_returns_empty_for_unknown_orderId() {
        assertThat(service.findById("9999-0000")).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew test --tests "com.baedal.support.OrderMockServiceTest" 2>&1 | tail -10
```

예상: `error: cannot find symbol` (OrderMockService 미존재)

- [ ] **Step 3: OrderMockService 구현**

`src/main/java/com/baedal/support/OrderMockService.java`:
```java
package com.baedal.support;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class OrderMockService {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        LocalDateTime now = LocalDateTime.now();

        Order o1234 = new Order(
                "2024-1234", "BHC치킨 역삼점",
                List.of(new OrderItem("허니콤보", 1, 18000), new OrderItem("콜라 1.25L", 1, 2000)),
                20000, now.minusMinutes(30), now.plusMinutes(15),
                "서울시 강남구 테헤란로 123", "역삼역 사거리", OrderStatus.DELIVERING);

        Order o1235 = new Order(
                "2024-1235", "맥도날드 강남점",
                List.of(new OrderItem("빅맥 세트", 2, 9500)),
                19000, now.minusMinutes(2), now.plusMinutes(40),
                "서울시 강남구 강남대로 456", null, OrderStatus.CREATED);

        Order o1236 = new Order(
                "2024-1236", "피자헛 서초점",
                List.of(new OrderItem("페퍼로니 피자 L", 1, 25000)),
                25000, now.minusHours(2), now.minusHours(1),
                "서울시 서초구 서초대로 789", null, OrderStatus.DELIVERED);

        Order o1237 = new Order(
                "2024-1237", "롯데리아 잠실점",
                List.of(new OrderItem("불고기버거 세트", 1, 7500)),
                7500, now.minusMinutes(15), now.plusMinutes(20),
                "서울시 송파구 올림픽로 101", null, OrderStatus.COOKING);

        Order o1238 = new Order(
                "2024-1238", "버거킹 홍대점",
                List.of(new OrderItem("와퍼 세트", 1, 9000)),
                9000, now.minusHours(3), now.minusHours(2),
                "서울시 마포구 홍대입구 202", null, OrderStatus.CREATED);
        o1238.cancel("고객 요청", now.minusHours(2).plusMinutes(5));

        Order o1239 = new Order(
                "2024-1239", "이디야커피 선릉점",
                List.of(new OrderItem("아이스 아메리카노", 2, 3500)),
                7000, now.minusMinutes(5), now.plusMinutes(35),
                "서울시 강남구 선릉로 303", null, OrderStatus.ACCEPTED);

        orders.put(o1234.getOrderId(), o1234);
        orders.put(o1235.getOrderId(), o1235);
        orders.put(o1236.getOrderId(), o1236);
        orders.put(o1237.getOrderId(), o1237);
        orders.put(o1238.getOrderId(), o1238);
        orders.put(o1239.getOrderId(), o1239);

        log.info("OrderMockService seeded — {}건", orders.size());
    }

    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.baedal.support.OrderMockServiceTest" 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, `4 tests completed`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/baedal/support/OrderMockService.java \
        src/test/java/com/baedal/support/OrderMockServiceTest.java
git commit -m "feat: add OrderMockService with 6 seeded orders"
```

---

## Task 4: OrderTools — @Tool 3개 + 단위 테스트

**Files:**
- Create: `src/main/java/com/baedal/support/OrderTools.java`
- Test: `src/test/java/com/baedal/support/OrderToolsTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/baedal/support/OrderToolsTest.java`:
```java
package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolsTest {

    private OrderMockService orderService;
    private OrderTools orderTools;

    @BeforeEach
    void setUp() {
        orderService = new OrderMockService();
        orderService.seed();
        orderTools = new OrderTools(orderService);
    }

    // ── cancelOrder 4-outcome ──────────────────────────────────────

    @Test
    void cancelOrder_returns_CANCELED_for_CREATED_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1235", "배달 지연");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.CANCELED);
    }

    @Test
    void cancelOrder_returns_CANCELED_for_ACCEPTED_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1239", "집 앞에 아무도 없어요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.CANCELED);
    }

    @Test
    void cancelOrder_returns_ALREADY_CANCELED_for_pre_canceled_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1238", "재확인");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.ALREADY_CANCELED);
        assertThat(result.message()).contains("취소 사유");
    }

    @Test
    void cancelOrder_returns_NOT_CANCELABLE_for_COOKING_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1237", "취소해주세요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_CANCELABLE);
    }

    @Test
    void cancelOrder_returns_NOT_CANCELABLE_for_DELIVERED_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1236", "취소해주세요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_CANCELABLE);
    }

    @Test
    void cancelOrder_returns_NOT_FOUND_for_unknown_orderId() {
        CancelOrderResult result = orderTools.cancelOrder("9999-0000", "취소해주세요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_FOUND);
    }

    // ── 멱등성: 두 번째 취소는 상태를 변경하지 않는다 ──────────────

    @Test
    void cancelOrder_second_call_returns_ALREADY_CANCELED() {
        orderTools.cancelOrder("2024-1239", "첫 번째 취소");
        CancelOrderResult second = orderTools.cancelOrder("2024-1239", "두 번째 취소");
        assertThat(second.outcome()).isEqualTo(CancelOrderResult.Outcome.ALREADY_CANCELED);
    }

    @Test
    void cancelOrder_second_call_does_not_overwrite_canceledReason() {
        orderTools.cancelOrder("2024-1239", "첫 번째 이유");
        orderTools.cancelOrder("2024-1239", "두 번째 이유");
        Order order = orderService.findById("2024-1239").orElseThrow();
        assertThat(order.getCanceledReason()).isEqualTo("첫 번째 이유");
    }

    // ── getOrderDetail ─────────────────────────────────────────────

    @Test
    void getOrderDetail_returns_menu_and_amount() {
        OrderDetailView view = orderTools.getOrderDetail("2024-1234");
        assertThat(view).isNotNull();
        assertThat(view.orderId()).isEqualTo("2024-1234");
        assertThat(view.items()).isNotEmpty();
        assertThat(view.totalAmount()).isEqualTo(20000);
    }

    @Test
    void getOrderDetail_returns_null_for_unknown_orderId() {
        assertThat(orderTools.getOrderDetail("9999-0000")).isNull();
    }

    // ── getDeliveryStatus ──────────────────────────────────────────

    @Test
    void getDeliveryStatus_returns_riderLocation_for_DELIVERING_order() {
        DeliveryStatusView view = orderTools.getDeliveryStatus("2024-1234");
        assertThat(view.riderLocation()).isEqualTo("역삼역 사거리");
    }

    @Test
    void getDeliveryStatus_returns_null_riderLocation_for_non_DELIVERING_order() {
        DeliveryStatusView view = orderTools.getDeliveryStatus("2024-1235");
        assertThat(view.riderLocation()).isNull();
    }

    @Test
    void getDeliveryStatus_returns_null_for_unknown_orderId() {
        assertThat(orderTools.getDeliveryStatus("2099-9999")).isNull();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew test --tests "com.baedal.support.OrderToolsTest" 2>&1 | tail -10
```

예상: `error: cannot find symbol` (OrderTools 미존재)

- [ ] **Step 3: OrderTools 구현**

`src/main/java/com/baedal/support/OrderTools.java`:
```java
package com.baedal.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderMockService orderService;

    @Tool(description = """
            주어진 주문번호의 상세 정보를 조회한다.
            고객이 메뉴, 수량, 금액, 주문 상태, 예상 배달 완료 시각을 물을 때 호출한다.
            배달 위치나 라이더 정보를 물을 때는 이 Tool을 호출하지 않는다.
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
            고객이 배달 위치, 라이더가 어디쯤 있는지, 배달이 언제 오는지를 물을 때 호출한다.
            메뉴나 금액을 물을 때는 이 Tool을 호출하지 않는다.
            배달 중(DELIVERING)인 주문에 대해서만 riderLocation이 반환되며,
            아직 배달이 시작되지 않았거나 이미 완료된 주문은 riderLocation이 null이다.
            존재하지 않는 주문번호면 null을 반환한다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "배달 상태를 조회할 주문번호. 예: 2024-1234") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);
        return orderService.findById(orderId).map(this::toDeliveryView).orElse(null);
    }

    @Tool(description = """
            주어진 주문번호의 주문을 취소한다.
            고객이 주문 취소를 명시적으로 요청할 때 호출한다.
            취소 가능 조건: 주문 상태가 CREATED 또는 ACCEPTED인 경우에만 가능하다.
            조리가 이미 시작된(COOKING 이후) 주문은 자동 취소가 불가하다.
            이미 취소된 주문을 다시 취소 요청하면 에러가 아닌 ALREADY_CANCELED 결과를 반환한다.
            결과의 outcome 필드로 성공/실패 사유를 확인할 수 있다: CANCELED, ALREADY_CANCELED, NOT_CANCELABLE, NOT_FOUND.
            """)
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "취소할 주문번호. 예: 2024-1234") String orderId,
            @ToolParam(description = "고객이 말한 취소 사유. 예: 집 앞에 아무도 없어요") String reason) {
        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);

        Order order = orderService.findById(orderId).orElse(null);
        if (order == null) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_FOUND,
                    "해당 주문번호를 찾을 수 없습니다.");
        }
        if (order.getStatus() == OrderStatus.CANCELED) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.ALREADY_CANCELED,
                    "해당 주문은 이미 취소된 상태입니다. (취소 사유: " + order.getCanceledReason() + ")");
        }
        if (!order.isCancelable()) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_CANCELABLE,
                    "조리가 이미 시작되어(" + order.getStatus() + ") 자동 취소가 불가합니다. 상담원 연결이 필요합니다.");
        }
        order.cancel(reason, LocalDateTime.now());
        return new CancelOrderResult(orderId, CancelOrderResult.Outcome.CANCELED,
                "주문이 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있습니다.");
    }

    private OrderDetailView toDetailView(Order order) {
        return new OrderDetailView(
                order.getOrderId(),
                order.getStoreName(),
                order.getItems().stream()
                        .map(i -> new OrderDetailView.Line(i.menuName(), i.quantity(), i.unitPrice()))
                        .toList(),
                order.getTotalAmount(),
                order.getStatus().name(),
                order.getOrderedAt(),
                order.getEstimatedDeliveryAt()
        );
    }

    private DeliveryStatusView toDeliveryView(Order order) {
        return new DeliveryStatusView(
                order.getOrderId(),
                order.getStatus().name(),
                order.getRiderLocation(),
                order.getEstimatedDeliveryAt()
        );
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.baedal.support.OrderToolsTest" 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, `13 tests completed`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/baedal/support/OrderTools.java \
        src/test/java/com/baedal/support/OrderToolsTest.java
git commit -m "feat: implement OrderTools with 3 @Tool methods and idempotent cancelOrder"
```

---

## Task 5: BaedalPrompt 업데이트 — [Tool 사용 규칙] 추가

**Files:**
- Modify: `src/main/java/com/baedal/support/BaedalPrompt.java`

- [ ] **Step 1: [Tool 사용 규칙] 섹션을 SYSTEM_PROMPT에 추가**

`src/main/java/com/baedal/support/BaedalPrompt.java`에서 `[응답 포맷]` 섹션 앞에 아래 내용을 삽입한다:

```java
            [Tool 사용 규칙]
            - 발화에 주문번호(YYYY-XXXX 형식)가 포함되면 반드시 주문 관련 Tool을 호출한다.
            - 고객이 배달 위치, 라이더 위치, 도착 시간을 물으면 getDeliveryStatus를 호출한다.
            - 고객이 메뉴, 금액, 주문 내역을 물으면 getOrderDetail을 호출한다.
            - 고객이 주문 취소를 요청하면 cancelOrder를 호출한다.
            - 주문번호 없이 취소를 요청하면 Tool을 호출하지 말고 주문번호를 먼저 되묻는다.
            - 일반 인사("안녕", "고마워")나 정책 질문("환불 정책 알려줘")에는 Tool을 호출하지 않는다.

```

수정 후 `SYSTEM_PROMPT`의 전체 내용:
```java
    public static final String SYSTEM_PROMPT = """
            [역할]
            당신은 배달 고객 상담 AI 에이전트입니다.
            주문/배달/취소/환불 관련 문의에 대해 정확하고 친절하게 응대합니다.

            [규칙]
            - 항상 존댓말을 사용합니다.
            - 주문번호·주소 등 정보가 부족하면 추측하지 말고 고객에게 되묻습니다.
            - 금액, 보상, 환불 가능 여부를 임의로 약속하지 않습니다.
            - 확인할 수 없는 사실은 "확인이 필요합니다"라고 답합니다.

            [금지]
            - 타 배달 플랫폼(쿠팡이츠, 요기요 등)을 추천하거나 비교하지 않습니다.
            - 사장님/라이더의 개인정보(연락처, 주소 등)를 절대 노출하지 않습니다.
            - 고객이 요구하더라도 쿠폰, 할인, 보상 지급을 약속하지 않습니다.
            - 건강 피해(알레르기, 식중독 등)에 대해 의료적 판단을 하지 않으며, 즉시 의료기관 방문을 안내합니다.

            [Tool 사용 규칙]
            - 발화에 주문번호(YYYY-XXXX 형식)가 포함되면 반드시 주문 관련 Tool을 호출한다.
            - 고객이 배달 위치, 라이더 위치, 도착 시간을 물으면 getDeliveryStatus를 호출한다.
            - 고객이 메뉴, 금액, 주문 내역을 물으면 getOrderDetail을 호출한다.
            - 고객이 주문 취소를 요청하면 cancelOrder를 호출한다.
            - 주문번호 없이 취소를 요청하면 Tool을 호출하지 말고 주문번호를 먼저 되묻는다.
            - 일반 인사("안녕", "고마워")나 정책 질문("환불 정책 알려줘")에는 Tool을 호출하지 않는다.

            [응답 포맷]
            1) 핵심 답변 (3문장 이내 요약)
            2) 필요 시 추가 확인 질문
            3) 다음에 취할 액션 제안

            [actionability 분류 기준]
            - IMMEDIATE: 고객이 앱에서 즉시 처리 가능 (접수 직후 주문 취소 등)
            - NEEDS_INFO: 주문번호 등 추가 정보만 있으면 처리 가능
            - NEEDS_REVIEW: 정책 검토 또는 담당팀 확인이 필요한 사안
            - ESCALATED: 법적·의료적 판단 등 상위팀 이관이 필요한 사안
            """;
```

- [ ] **Step 2: 기존 BaedalPromptTest가 여전히 통과하는지 확인**

```bash
./gradlew test --tests "com.baedal.support.BaedalPromptTest" 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/baedal/support/BaedalPrompt.java
git commit -m "feat: add [Tool 사용 규칙] section to BaedalPrompt.SYSTEM_PROMPT"
```

---

## Task 6: AssistantController — /api/v1/assistant (생성자 패턴)

**Files:**
- Create: `src/main/java/com/baedal/support/AssistantController.java`
- Test: `src/test/java/com/baedal/support/AssistantControllerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/baedal/support/AssistantControllerTest.java`:
```java
package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssistantController.class)
class AssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatClient.Builder builder;

    @MockBean
    private PerformanceLoggingAdvisor performanceAdvisor;

    @MockBean
    private OrderTools orderTools;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(performanceAdvisor)).thenReturn(builder);
        when(builder.defaultTools(orderTools)).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("배달 현황 확인 중입니다.");
    }

    @Test
    void ask_returns_200_with_string_response() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"주문번호 2024-1234 어디쯤이에요?\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void ask_registers_order_tools_in_constructor() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"테스트\"}"))
                .andExpect(status().isOk());

        verify(builder).defaultTools(orderTools);
    }

    @Test
    void ask_uses_system_prompt() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"테스트\"}"))
                .andExpect(status().isOk());

        verify(builder).defaultSystem(BaedalPrompt.SYSTEM_PROMPT);
    }

    @Test
    void ask_returns_400_when_message_is_blank() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ask_returns_400_when_message_exceeds_1000_chars() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"" + "A".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew test --tests "com.baedal.support.AssistantControllerTest" 2>&1 | tail -10
```

예상: `error: cannot find symbol` (AssistantController 미존재)

- [ ] **Step 3: AssistantController 구현**

`src/main/java/com/baedal/support/AssistantController.java`:
```java
package com.baedal.support;

import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    public AssistantController(ChatClient.Builder builder,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public String ask(@Valid @RequestBody ChatRequest req) {
        return chatClient.prompt()
                .user(req.message())
                .call()
                .content();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.baedal.support.AssistantControllerTest" 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, `5 tests completed`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/baedal/support/AssistantController.java \
        src/test/java/com/baedal/support/AssistantControllerTest.java
git commit -m "feat: add AssistantController at /api/v1/assistant with constructor pattern"
```

---

## Task 7: SupportController — OrderTools 추가

**Files:**
- Modify: `src/main/java/com/baedal/support/SupportController.java`
- Modify: `src/test/java/com/baedal/support/SupportControllerTest.java`

> **설계 노트**: `SupportController`는 기존 `@WebMvcTest` 호환성 유지를 위해 `.tools(orderTools)` (per-request) 방식을 사용한다. `AssistantController`의 생성자 패턴과 달리, `ChatClient.Builder` 싱글톤에 Tool을 누적하지 않으면서 매 요청에 Tool을 주입한다.

- [ ] **Step 1: SupportControllerTest에 tools mock 추가 (선행)**

`SupportControllerTest.java`의 `setUp()` 메서드에 두 줄을 추가한다.

현재 `setUp()` 안에서:
```java
when(builder.defaultSystem(anyString())).thenReturn(builder);
when(builder.defaultAdvisors(performanceAdvisor)).thenReturn(builder);
when(builder.build()).thenReturn(chatClient);
when(chatClient.prompt()).thenReturn(requestSpec);
when(requestSpec.user(anyString())).thenReturn(requestSpec);
when(requestSpec.call()).thenReturn(callSpec);
when(callSpec.entity(SupportResponse.class)).thenReturn(deliveryResponse);
```

아래 두 줄을 **`when(requestSpec.user(...)` 뒤에** 추가한다:
```java
when(requestSpec.tools(orderTools)).thenReturn(requestSpec);
```

그리고 `triage_registers_performance_logging_advisor` 테스트 옆에 새 테스트를 추가한다:
```java
@Test
void triage_registers_order_tools_per_request() throws Exception {
    mockMvc.perform(post("/api/v1/support")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\": \"테스트\"}"))
            .andExpect(status().isOk());

    verify(requestSpec).tools(orderTools);
}
```

- [ ] **Step 2: 새 테스트가 실패하는지 확인**

```bash
./gradlew test --tests "com.baedal.support.SupportControllerTest" 2>&1 | tail -15
```

예상: `triage_registers_order_tools_per_request` FAILED

- [ ] **Step 3: SupportController에 .tools(orderTools) 추가**

`SupportController.java`를 아래와 같이 수정한다. `@RequiredArgsConstructor`를 제거하고 `OrderTools`를 생성자로 주입한다:

```java
package com.baedal.support;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;
    private final OrderTools orderTools;

    public SupportController(ChatClient.Builder builder,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             OrderTools orderTools) {
        this.builder = builder;
        this.performanceAdvisor = performanceAdvisor;
        this.orderTools = orderTools;
    }

    @PostMapping
    public SupportResponse triage(@Valid @RequestBody ChatRequest req) {
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .build()
                .prompt()
                .user(req.message())
                .tools(orderTools)
                .call()
                .entity(SupportResponse.class);
    }
}
```

- [ ] **Step 4: SupportControllerTest 전체 통과 확인**

```bash
./gradlew test --tests "com.baedal.support.SupportControllerTest" 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, `5 tests completed`

- [ ] **Step 5: 전체 테스트 회귀 없는지 확인**

```bash
./gradlew test 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/baedal/support/SupportController.java \
        src/test/java/com/baedal/support/SupportControllerTest.java
git commit -m "feat: register OrderTools in SupportController per request"
```

---

## Task 8: 다국어 정확도 테스트 스캐폴딩

**Files:**
- Create: `src/test/java/com/baedal/support/OrderToolsMultilingualAccuracyTest.java`

이 테스트는 실제 Ollama가 필요한 통합 테스트이므로 `@Tag("multilingual")`로 격리한다. CI에서는 건너뛰고, 수동으로 실행한다.

- [ ] **Step 1: 다국어 정확도 테스트 생성**

`src/test/java/com/baedal/support/OrderToolsMultilingualAccuracyTest.java`:
```java
package com.baedal.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 한국어 description 사용 시 다국어 발화에서의 Tool 호출 성공률을 측정한다.
 *
 * 실행 방법 (Ollama + qwen2.5 실행 중이어야 함):
 *   ./gradlew test --tests "com.baedal.support.OrderToolsMultilingualAccuracyTest"
 *
 * 결과를 README의 다국어 정확도 표에 기록할 것.
 * 성공률이 3/5 미만이면 description을 영어 또는 한국어+영어 병기로 전환을 검토한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("multilingual")
class OrderToolsMultilingualAccuracyTest {

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> deliveryStatusQueries() {
        return Stream.of(
            arguments("ko",       "주문번호 2024-1234 배달 어디쯤에 있어요?"),
            arguments("en",       "Where is my order 2024-1234?"),
            arguments("ja",       "注文番号2024-1234の配達状況を教えてください"),
            arguments("zh",       "订单2024-1234现在在哪里？"),
            arguments("en-casual","is order 2024-1234 on its way?")
        );
    }

    /**
     * 각 언어로 배달 위치를 물었을 때 getDeliveryStatus Tool이 호출되는지 확인.
     * 콘솔에서 "[Tool] getDeliveryStatus(orderId=2024-1234)" 로그가 찍히면 성공.
     * 응답 본문에 "역삼역 사거리"가 포함되면 Tool 호출 + 정확한 응답 모두 성공.
     */
    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("deliveryStatusQueries")
    void getDeliveryStatus_is_called_for_language(String lang, String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 결과를 표로 기록하기 위해 출력 (테스트는 assert 하지 않고 관찰값만 수집)
        System.out.printf("[multilingual][%s] contains '역삼역 사거리': %s%n",
                lang, body.contains("역삼역 사거리"));
        System.out.printf("[multilingual][%s] response: %s%n", lang, body);
    }
}
```

- [ ] **Step 2: 컴파일 확인 (Ollama 없이)**

```bash
./gradlew compileTestJava 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/test/java/com/baedal/support/OrderToolsMultilingualAccuracyTest.java
git commit -m "test: add multilingual accuracy test scaffold for OrderTools description"
```

---

## 최종 검증

- [ ] **전체 테스트 통과**

```bash
./gradlew test 2>&1 | tail -15
```

예상: `BUILD SUCCESSFUL` (multilingual 테스트는 Ollama 필요하므로 제외하거나 실패해도 OK)

- [ ] **애플리케이션 실행 확인**

```bash
./gradlew bootRun 2>&1 | grep -E "seeded|Started|ERROR" | head -5
```

예상: `OrderMockService seeded — 6건`, `Started BaedalSupportApplication`

- [ ] **스펙 자가 점검 체크리스트 완료**

`docs/superpowers/specs/2026-05-24-tool-calling-boundary-design.md`의 자가 점검 항목 6개를 직접 답할 수 있는지 확인:

1. `OrderDetailView`에서 뺀 필드 4개와 이유
2. `isCancelable()`이 Tool이 아닌 도메인에 있는 이유
3. `ALREADY_CANCELED`가 예외가 아닌 이유
4. 2단계 실험 전제 — `2024-1238`에 `canceledReason`이 채워져 있는가
5. description 언어 결정 근거와 다국어 전환 조건
6. 생성자 패턴 미사용 시 몇 번째 요청에서 깨지는지
