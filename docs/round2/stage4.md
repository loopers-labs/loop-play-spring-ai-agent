# 4단계: Observability + AI 코드 리뷰

**목표**: Tool 왕복을 로그로 직접 관찰하고, AI가 만든 Tool 코드의 프로덕션 결함을 비판적으로 검토한다.

## Tool 왕복 관찰

`/api/v1/assistant`에 `"주문번호 2024-1234 배달 어디쯤에 있어요?"`를 보내면 LLM이 두 번 호출된다.

### Tool 실행 시점 로그

```
DEBUG DefaultToolCallingManager  : Executing tool call: getDeliveryStatus
DEBUG MethodToolCallback         : Starting execution of tool: getDeliveryStatus
INFO  OrderTools                 : [Tool] getDeliveryStatus(orderId=2024-1234)
DEBUG MethodToolCallback         : Successful execution of tool: getDeliveryStatus
DEBUG DefaultToolCallResultConverter : Converting tool result to JSON.
INFO  PerformanceLoggingAdvisor  : LLM 호출 완료 — 37199ms | 입력 토큰: 1203 | 출력 토큰: 90 | 총 토큰: 1293
```

`PerformanceLoggingAdvisor`가 찍는 1203 토큰은 2차 LLM 호출(Tool 결과를 포함한 프롬프트)의 입력 토큰이다. 1차 호출은 별도로 노출되지 않는다. 토큰 차이가 그 증거다.

---

## 입력 토큰 비교 — Round 1 vs Round 2

| 엔드포인트 | 질문 | 입력 토큰 | 비고 |
|---|---|---|---|
| `/api/v1/chat` (Round 1, Tool 없음) | `"안녕하세요"` | ~10 | PerformanceLoggingAdvisor 없음 — 측정 불가, 사용자 메시지만 포함 |
| `/api/v1/assistant` (Round 2, Tool 3개) | `"안녕하세요"` | **467** | 시스템 프롬프트 + 3개 Tool 스키마 |
| `/api/v1/assistant` (Tool 실제 호출) | `"2024-1234 어디쯤?"` | **1203** | 위 + Tool 실행 결과 JSON |

**입력 토큰 차이의 원인**

`/api/v1/chat`(~10토큰)과 `/api/v1/assistant`(467토큰) 차이 약 450토큰은 두 가지에서 온다. 첫째, 시스템 프롬프트. 둘째, Tool 3개의 JSON 스키마 — Spring AI가 `@Tool` / `@ToolParam`을 직렬화해 프롬프트에 자동으로 포함한다. 모델은 이 스키마를 읽고 어떤 Tool을 언제 호출할지 판단한다.

Tool이 실제로 호출되면 입력 토큰이 1203까지 올라간다. 추가된 736토큰은 Tool 실행 결과 JSON이 2차 LLM 호출 프롬프트에 붙기 때문이다.

**Tool 호출 시 Round 1 대비 토큰 배율**

Round 1 ~10토큰 → Round 2 Tool 호출 시 1203토큰 → **약 120배**.

---

## AI 코드 리뷰 — 프로덕션 결함 찾기

Codex에게 아래와 같이 요청:

> `"Spring AI 1.0으로 배달 주문 취소 Tool을 만들어줘. @Tool 어노테이션을 써야 해."`

### AI가 생성한 원본 코드

```java
package com.maiorem.demo.order;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DeliveryOrderTool {

    private final DeliveryOrderService deliveryOrderService;

    public DeliveryOrderTool(DeliveryOrderService deliveryOrderService) {
        this.deliveryOrderService = deliveryOrderService;
    }

    @Tool(
            name = "cancel_delivery_order",
            description = "배달 주문을 취소한다. 주문 ID와 취소 사유가 확인된 경우에만 호출하고, 이미 배달 완료되었거나 이미 취소된 주문에는 사용하지 않는다."
    )
    public OrderCancellationResult cancelDeliveryOrder(
            @ToolParam(description = "취소할 배달 주문 번호. 예: ORD-1001") String orderId,
            @ToolParam(description = "고객이 제공한 주문 취소 사유") String cancelReason
    ) {
        return deliveryOrderService.cancelOrder(orderId, cancelReason);
    }
}
```

### 발견한 결함 3가지

| # | 결함 | 이번 수업에서 배운 개선 방안 |
|---|---|---|
| 1 | 로깅 없음 — Tool이 언제 어떤 파라미터로 실행됐는지 기록이 없다 | `log.info("[Tool] cancelDeliveryOrder(orderId={}, cancelReason={})", ...)` 추가 |
| 2 | description에 실패 반환값 명시 없음 — LLM에게 "호출하지 말라"고만 했고, 실수로 호출했을 때 어떤 값이 오는지 모른다 | "취소 불가 상태이거나 존재하지 않으면 실패 이유를 반환한다" 문구 추가 |
| 3 | 코드 레벨 멱등성 없음 — 서비스에 전부 위임하므로 `deliveryOrderService.cancelOrder()`가 이미 취소된 주문에서 예외를 던지면 LLM이 fallback 못 한다 | Tool 메서드 안에서 직접 상태 체크 → `ALREADY_CANCELED` 반환 보장 |

**역설적으로 잘 된 것**: description이 "언제 호출하고 언제 호출하지 않는지"를 명시했다. 이번 수업에서 배운 항목 1번이다. Codex가 description 작성법은 익힌 것 같다.

### 개선한 코드

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryOrderTool {

    private final DeliveryOrderService deliveryOrderService;

    @Tool(description = """
            배달 주문을 취소한다.
            주문 ID와 취소 사유가 확인된 경우에만 호출한다.
            취소 불가 상태(배달 완료, 조리 중)이거나 존재하지 않는 주문번호면 실패 이유를 반환한다.
            이미 취소된 주문을 다시 취소 요청하면 ALREADY_CANCELED를 반환한다 (에러가 아님).
            """)
    public OrderCancellationResult cancelDeliveryOrder(
            @ToolParam(description = "취소할 배달 주문 번호. 예: ORD-1001") String orderId,
            @ToolParam(description = "고객이 제공한 주문 취소 사유") String cancelReason
    ) {
        log.info("[Tool] cancelDeliveryOrder(orderId={}, cancelReason={})", orderId, cancelReason);

        var orderOpt = deliveryOrderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            return new OrderCancellationResult(orderId, Outcome.NOT_FOUND, "존재하지 않는 주문번호입니다.");
        }

        var order = orderOpt.get();
        if (order.isCanceled()) {
            return new OrderCancellationResult(orderId, Outcome.ALREADY_CANCELED,
                    "이미 취소된 주문입니다. 취소 사유: " + order.canceledReason());
        }
        if (!order.isCancelable()) {
            return new OrderCancellationResult(orderId, Outcome.NOT_CANCELABLE,
                    "취소할 수 없습니다. 현재 상태: " + order.status());
        }

        deliveryOrderService.cancel(orderId, cancelReason);
        return new OrderCancellationResult(orderId, Outcome.CANCELED, "주문이 성공적으로 취소되었습니다.");
    }
}
```

원본과 개선 코드의 차이:
- `log.info(...)` — 감사 로그
- 서비스에 위임하기 전에 Tool 레벨에서 직접 상태 체크 — 멱등성 코드 보장
- description에 "실패 시 어떤 값을 반환하는가" 추가

---

## 회고

---

### 회고 1 — Codex description은 잘 썼는데 코드는 껍데기였다

Codex가 생성한 description은 꽤 괜찮았다.

```
배달 주문을 취소한다. 주문 ID와 취소 사유가 확인된 경우에만 호출하고,
이미 배달 완료되었거나 이미 취소된 주문에는 사용하지 않는다.
```

"언제 호출하는가"와 "언제 호출하지 않는가"가 둘 다 있다. 이번 수업에서 배운 description 항목 1번이다.

그런데 정작 코드가 `return deliveryOrderService.cancelOrder(orderId, cancelReason)` 한 줄이었다. 모든 책임을 서비스에 위임했다. 서비스가 예외를 던지면 LLM은 fallback 못 한다. 로그도 없어서 어떤 Tool이 언제 실행됐는지 알 수 없다.

description을 잘 쓰는 것과 코드가 제대로 방어하는 것은 다른 문제다. 

---

### 회고 2 — Tool 왕복이 토큰으로 보인다

"안녕하세요" 한 마디에 `/api/v1/chat`은 ~10토큰이었고 `/api/v1/assistant`는 467토큰이었다. 

Tool 3개의 JSON 스키마가 자동으로 프롬프트에 붙어서다. `@Tool` / `@ToolParam` annotation을 Spring AI가 직렬화해서 넣는다. 이게 매 요청마다 포함된다.

Tool이 실제로 호출되면 1203토큰으로 올라간다. Tool 결과 JSON이 2차 LLM 호출에 추가되기 때문이다. Tool 하나를 쓰는 것만으로 Round 1 대비 120배 토큰이 된다. Tool을 많이 등록할수록, Tool 결과가 클수록 비용이 올라간다는 걸 숫자로 봤다.
