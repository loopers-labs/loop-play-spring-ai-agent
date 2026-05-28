# 4단계 — Observability + AI 코드 리뷰 보고서

> Round 2 / 4단계 — Tool 호출 왕복 로그 + Round 1 vs Round 2 토큰 비교 + GPT-5.5 가 만든 코드의 프로덕션 결함 분석.
> Round 1 부분점수 1위 영역 — *"AI 코드 리뷰의 깊이"* 가 평가 핵심.

## Phase 4-1 / 4-2 — Tool 왕복 + 토큰 비교

### Tool 호출 흐름 4단계 (Spring AI 1.0)

`PerformanceLoggingAdvisor` 에 요청 시점 로깅 (`[LLM-REQ]`) 추가하여 캡처:

```
입력: "주문번호 2024-1234 배달 어디쯤이에요?"
└─────────────────────────────────────────────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ [+0초] [LLM-REQ] messages=2                                              │
│        userMessage='주문번호 2024-1234 배달 어디쯤이에요?'                │
│                                                                          │
│  요청 본문 (Ollama 에 전송):                                              │
│  ├ system: SYSTEM_PROMPT (~3,000 토큰)                                   │
│  ├ user:   "주문번호 2024-1234..."                                       │
│  └ tools:  [                                                              │
│      getOrderDetail   { description, parameters: {orderId} },             │
│      getDeliveryStatus { description, parameters: {orderId} },            │
│      cancelOrder      { description, parameters: {orderId, reason} }      │
│    ]                                                                      │
└─────────────────────────────────────────────────────────────────────────┘
                              ▼ 1차 LLM 추론 (약 1.5초)
                              ▼ Ollama 응답: tool_calls = [getDeliveryStatus]
                              ▼ Spring AI 가 Tool 자동 디스패치
┌─────────────────────────────────────────────────────────────────────────┐
│ [+1.5초] [Tool] getDeliveryStatus(orderId=2024-1234)                      │
│                                                                          │
│   OrderTools.getDeliveryStatus("2024-1234")                              │
│   → OrderMockService.findById("2024-1234") → Order 반환                  │
│   → toDeliveryView(order) → DeliveryStatusView 반환                      │
│      { status: "DELIVERING",                                             │
│        riderLocation: "역삼역 사거리...",                                │
│        estimatedDeliveryAt: "2026-05-24T11:18:32",                       │
│        message: "라이더가 배달 중입니다." }                              │
└─────────────────────────────────────────────────────────────────────────┘
                              ▼ Spring AI 가 ToolResponseMessage 자동 추가
                              ▼ 2차 LLM 호출 시작
┌─────────────────────────────────────────────────────────────────────────┐
│ [+1.5~3.2초] 2차 LLM 호출 (직접 advisor 로그 없음 — chain 내부 처리)     │
│                                                                          │
│  요청 본문:                                                              │
│  ├ system: SYSTEM_PROMPT (그대로)                                        │
│  ├ user:   "주문번호 2024-1234..." (그대로)                             │
│  ├ tools:  [...]                  (그대로)                              │
│  ├ assistant: { tool_calls: [...] }    (1차 응답)                       │
│  └ tool:   { content: <DeliveryStatusView JSON> }  ← Tool 결과           │
└─────────────────────────────────────────────────────────────────────────┘
                              ▼ Ollama 최종 자연어 응답
┌─────────────────────────────────────────────────────────────────────────┐
│ [+3.2초] [LLM] elapsedMs=3189 inputTokens=7246 outputTokens=102          │
│                                                                          │
│  최종 응답:                                                              │
│  "주문 2024-1234는 현재 배달 중이며 라이더가 역삼역 사거리 부근에        │
│   있습니다. 약 15분 내 도착 예정이에요."                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

### advisor 가 1회만 발동하는 이유

`PerformanceLoggingAdvisor (CallAdvisor)` 의 `adviseCall()` 은 **chain 의 가장 바깥쪽 1회만 호출**.
Spring AI 의 Tool execution 은 chain *내부* 에서 자동 처리 → 1차 + 2차 LLM 호출이 advisor 입장에서는 *하나의 왕복*.

→ `inputTokens=7246` 은 **2차 호출의 컨텍스트 누적값** (1차 입력 + Tool 결과 모두 포함).
→ 1차 / 2차 분리 측정하려면 advisor 가 chain *안* 에 위치하거나 별도 Ollama wire-level 로깅 필요.

이건 본 라운드의 **4단계 Observability 한계 발견** — `inputTokens` 단일 값만 보면 *어디서* 비용이 폭증했는지 분리 어려움.

### Round 1 vs Round 2 입력 토큰 비교

| # | 엔드포인트 | 입력 | Tool 호출? | inputTokens | outputTokens | elapsedMs |
|---|---|---|---|---:|---:|---:|
| (1) | `/api/v1/chat` | "안녕하세요" | 안 함 | 3,545 | 41 | 13,862 (cold) |
| (2) | `/api/v1/assistant` | "안녕하세요" | 안 함 | 3,545 | 51 | 1,335 (warm) |
| (3) | `/api/v1/assistant` | "주문번호 2024-1234 어디쯤?" | ✅ | **7,246** | 102 | 3,189 |

⚠️ **(1) 과 (2) 가 동일 3,545** — 우리 Round 2 통합 구조에서 *두 엔드포인트가 동일 ChatClient 공유* (`SupportService.chatClient`).

→ quest 명세의 *Round 1 (Tool 없음) vs Round 2 (Tool 있음)* 분리 비교는 **불가**. 다만:
- (1)·(2) vs (3) 비교 — **Tool 호출 *발동* 여부에 따른 토큰 비용**: 3,545 → 7,246 = **2.04 배**
- 이 비교가 quest 가 묻는 *"두 엔드포인트 입력 토큰 차이가 무엇에서 오는지"* 의 답을 줌

### inputTokens 차이의 출처 분석

```
미발동 3,545 ≈
  system prompt (BaedalPrompt.SYSTEM_PROMPT)          ~ 2,500 토큰  (≈70%)
  + tools 정의 JSON 스키마 (3 Tool × description+params) ~ 1,000 토큰  (≈28%)
  + 사용자 메시지 (짧음)                                  ~    5 토큰   (≈0.1%)
  + 메타 헤더 (역할 구분 등)                              ~   40 토큰   (≈1%)

발동 7,246 ≈
  위 컨텍스트 (3,545)
  + assistant tool_calls 메시지 (1차 응답)            ~  100 토큰
  + tool 결과 메시지 (DeliveryStatusView JSON)        ~  200 토큰
  + 사용자 메시지 + system prompt 재전송 (2차 호출)    ~ 3,400 토큰
                                                       ─────────
                                                       ≈ 3,700 토큰 추가
```

→ **2차 LLM 호출이 비용 폭증의 주범**. Tool 결과 자체는 200 토큰이지만 *1차 컨텍스트 전체를 다시 전송* 해야 LLM 이 누적 추론.

→ Tool 호출 횟수가 N 번 늘면 토큰이 *선형*이 아닌 *누적적* 으로 증가 (각 호출마다 이전 모든 컨텍스트 재전송).

### Round 1 시점 토큰 (4단계 보고서) 과의 비교

| 시점 | inputTokens | 차이 |
|---|---:|---|
| Round 1 4단계 (시나리오 3종) | 2,651 ~ 2,656 평균 | system prompt 약 2,500 + 사용자 메시지 |
| Round 2 현재 (미발동) | 3,545 | **+890 토큰** = `[Tool 사용 규칙]` 섹션 + Tool 정의 JSON 스키마 |
| Round 2 (발동) | 7,246 | **+4,591 토큰 (Round 1 대비 2.73 배)** |

→ Round 2 는 *Tool 없는 호출에도* Round 1 보다 33% 더 비쌈. Tool 정의 JSON 스키마가 매 호출 system prompt 와 함께 전송되기 때문.

### 운영 시사점

| 자리 | 영향 |
|---|---|
| Tool 등록 자체 비용 | 매 호출 ~1,000 토큰 누적. 등록 Tool 개수에 비례 |
| Tool 호출 발동 비용 | 2.04 배 폭증. 짧은 응답일수록 *비율* 더 큼 |
| Tool description 길이 vs 호출률 | 3단계 발견 — 너무 짧으면 호출 회피 (B 60%), 적절하면 정확 (A 100%). 효율 최적점 찾기 필요 |
| 운영 모니터링 | `inputTokens` 만 보면 *어느 호출이 비용 비쌌는지* 분리 어려움 → wire-level 로깅 또는 chain 내부 advisor 필요 |

---

## Phase 4-3 — AI 코드 리뷰 (GPT-5.5)

### 프롬프트 (quest 명세 그대로)

```
"Spring AI 1.0으로 배달 주문 취소 Tool을 만들어줘. @Tool 어노테이션을 써야 해."
```

### GPT-5.5 가 생성한 코드 구조

```
ai/DeliveryAiController         POST /ai/delivery/chat
ai/DeliveryAiAgentService       ChatClient + system prompt + tools(...)
ai/tool/DeliveryOrderCancelTool @Tool cancelDeliveryOrder
order/application/OrderCancelService    @Transactional cancel(...)
order/application/dto/CancelOrderCommand record (orderId, customerId, reason, requestedBy)
order/application/dto/CancelOrderResult  record (boolean success, ...)
order/domain/Order              @Entity, isCancelable/cancel/isOwnedBy
order/domain/OrderRepository    JpaRepository
order/domain/OrderStatus        6개 enum
```

### 잘 한 부분 (먼저 인정)

| 항목 | 평가 |
|---|---|
| Tool description 한국어 + 호출 시점 + 사용 규칙 + 보상 약속 금지 | ✅ 3단계 발견 *"키워드 풍부함"* 충족 |
| 도메인 분리 (Tool → Service → Repository) | ✅ 클린 |
| `@Transactional` 적용 | ✅ |
| 본인 소유 검증 (`order.isOwnedBy(customerId)`) | ✅ 우리에게 없는 권한 검증 — 학습 가치 |
| `.tools(...)` 호출별 적용 | ✅ defaultTools 누적 빌더 bug 회피 (1단계 4단계 보고서 패턴) |
| README 메타 원칙 ("LLM 이 직접 DB 수정 X" / "customerId 서버 값") | ✅ |
| system prompt 에 한국어 [규칙]·[금지] 적용 | ✅ Round 1 패턴과 일치 |

→ **GPT-5.5 코드는 1단계 수준은 통과**. *프로덕션 운영의 디테일* 에서 결함.

### 🔴 프로덕션 결함 3가지 (quest 평가 핵심)

#### 결함 1. **멱등성 분기 부재**

**GPT-5.5 코드 — `OrderCancelService.cancel()`**:
```java
@Transactional
public CancelOrderResult cancel(CancelOrderCommand command) {
    Order order = orderRepository.findById(command.orderId())
        .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

    if (!order.isOwnedBy(command.customerId())) {
        return CancelOrderResult.fail(..., "본인 주문만 취소할 수 있습니다.");
    }
    if (!order.isCancelable()) {
        return CancelOrderResult.fail(..., "현재 주문 상태에서는 취소가 어렵습니다.");
    }
    order.cancel(command.cancelReason(), command.requestedBy());
    return CancelOrderResult.success(..., "주문이 정상적으로 취소되었습니다.");
}
```

**문제**:
- `Order.isCancelable()` 은 `ORDERED || ACCEPTED` 만 허용 → 이미 `CANCELED` 인 주문 재요청 시 `false` 반환
- → `fail("현재 주문 상태에서는 취소가 어렵습니다")` 응답
- *"이미 취소되었음"* vs *"조리 시작되어 못 함"* 이 **같은 message 로 묶임**
- 더 심각 — `Order.cancel()` 안에 `if (!isCancelable()) throw new IllegalStateException(...)` 추가 검사 — defense-in-depth 지만 *응답 분기로 이어지지 않음*

**프로덕션 사고 시나리오**:
1. 고객이 *"주문 취소"* 요청 → 첫 호출 `success=true` → 매장 알림 발송 + 결제 환불 진행 시작
2. 결제 PG 응답 늦어서 *"환불 처리 중"* 메시지가 LLM 으로 안 도달
3. 고객이 *"진짜 취소된 거 맞나요? 한 번 더 취소해주세요"* 재요청
4. 두 번째 호출 → `fail("현재 주문 상태에서는 취소가 어렵습니다")` (실제로는 *"이미 취소되었다"* 인데)
5. → 고객 *"왜 못 한다고 하지?"* 혼란
6. → 운영자 알림에는 *"취소 불가"* 가 두 건 — 분기 못 함

**우리 라운드의 정답 적용**:
```java
if (order.getStatus() == OrderStatus.CANCELED) {
    return CancelOrderResult.alreadyCanceled(
        order.getId(), order.getStatus().name(),
        "이미 취소된 주문입니다. 사유: " + order.getCancelReason());
}
if (!order.isCancelable()) {
    return CancelOrderResult.notCancelable(...);
}
order.cancel(...);
return CancelOrderResult.canceled(...);
```

→ **Round 2 2단계 보고서의 멱등성 핵심 결론**: 같은 응답 *재전달* + 첫 호출의 reason 유지.

#### 결함 2. **예외를 그대로 throw — LLM Fallback 불가**

**GPT-5.5 코드**:
```java
Order order = orderRepository.findById(command.orderId())
    .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

// 그리고 Order.cancel() 안에:
public void cancel(String cancelReason, String canceledBy) {
    if (!isCancelable()) {
        throw new IllegalStateException("취소할 수 없는 주문 상태입니다.");
    }
    ...
}
```

**문제**:
- Spring AI 의 Tool 호출 중 `IllegalArgumentException` / `IllegalStateException` 발생 → `ToolExecutionException` 으로 wrap → ChatClient 호출 자체가 실패
- LLM 입장에서 *"Tool 호출 실패"* 만 알고 *"왜 실패했는지"* 알 길 없음
- 정상 fallback (*"주문번호를 찾을 수 없습니다. 다시 한 번 확인해주세요"* 같은 자연어 안내) 불가
- 운영자는 *"시스템 에러"* 와 *"고객 입력 실수"* 를 구분 못 함

**우리 라운드의 정답** — Outcome 반환:
```java
var maybeOrder = orderService.findById(orderId);
if (maybeOrder.isEmpty()) {
    return new CancelOrderResult(orderId, Outcome.NOT_FOUND,
        "해당 주문번호를 찾을 수 없습니다.");
}
```

→ Round 1 의 *"예외 그대로 throw 하지 마라"* + Round 2 *"NOT_FOUND Outcome"* 정신.

**검증**: 우리 `cancelOrder` Tool 에 `9999-0000` 호출 시:
- 응답: `{"outcome": "NOT_FOUND", "message": "해당 주문번호를 찾을 수 없습니다."}`
- LLM 자연어: *"주문번호 9999-0000를 찾을 수 없습니다. 다시 한 번 확인하신 후 요청해주세요."*

→ GPT-5.5 코드는 이 흐름 불가.

#### 결함 3. **`boolean success` — Outcome 구분 없음**

**GPT-5.5 코드**:
```java
public record CancelOrderResult(
    boolean success,
    Long orderId,
    String orderStatus,
    String message
) {
    public static CancelOrderResult success(...);
    public static CancelOrderResult fail(...);
}
```

**문제**:
실패 케이스가 모두 `success = false` 로 묶임:
- 본인 소유 아님 (`isOwnedBy = false`)
- 취소 불가 상태 (`isCancelable = false`, COOKING/DELIVERING/COMPLETED)
- 이미 취소됨 (`status = CANCELED`) — 결함 1과 결합
- (예외 throw 케이스는 아예 결과 받지 못함)

후속 시스템 분기 불가:
- 결제 PG 자동 환불 트리거 → `if (result.success)` 만 보고 환불 처리 시도 → `false` 케이스에서 어떤 사유인지 모름
- 매장 알림 → 모든 `false` 가 같은 알림 (구분 불가)
- 고객 재시도 처리 — `NOT_FOUND` 면 *"주문번호 확인 요청"*, `NOT_CANCELABLE` 면 *"매장 직접 연락"* 등 분기되어야

LLM 도 `message` 자연어 문자열을 *패턴 매칭* 으로 해석 → 메시지 텍스트 변경 시 동작 깨짐.

**우리 라운드의 정답** — Outcome enum 4~5종:
```java
public record CancelOrderResult(
    Long orderId, Outcome outcome, String orderStatus, String message
) {
    public enum Outcome {
        CANCELED,
        ALREADY_CANCELED,   // 멱등 (결함 1 해소)
        NOT_CANCELABLE,
        NOT_FOUND,          // 예외 대신 결과 (결함 2 해소)
        NOT_OWNED           // GPT 의 isOwnedBy 검증을 Outcome 으로 분리
    }
}
```

후속 시스템:
- `CANCELED` → 환불 트리거 + 매장 알림
- `ALREADY_CANCELED` → 알림 안 함 (멱등)
- `NOT_CANCELABLE` → 매장 확인 단계
- `NOT_FOUND` → 고객 재질의
- `NOT_OWNED` → 보안 알림 + 감사 로그

### 우리 라운드 자산으로 개선 가능한 형태 (3 결함 모두 해소)

```java
// 개선된 OrderCancelService.cancel()
@Transactional
public CancelOrderResult cancel(CancelOrderCommand command) {
    log.info("[Tool] cancelOrder(orderId={}, customerId={}, reason={})",   // ← 로깅 추가 (결함 4)
        command.orderId(), command.customerId(), command.cancelReason());

    var maybeOrder = orderRepository.findById(command.orderId());
    if (maybeOrder.isEmpty()) {                                              // ← 결함 2 해소 (NOT_FOUND)
        return CancelOrderResult.notFound(command.orderId(),
            "해당 주문번호를 찾을 수 없습니다.");
    }
    Order order = maybeOrder.get();

    if (!order.isOwnedBy(command.customerId())) {
        return CancelOrderResult.notOwned(order.getId(), order.getStatus().name(),
            "본인 주문만 취소할 수 있습니다.");
    }

    if (order.getStatus() == OrderStatus.CANCELED) {                         // ← 결함 1 해소 (ALREADY_CANCELED)
        return CancelOrderResult.alreadyCanceled(order.getId(),
            order.getStatus().name(),
            "이미 취소된 주문입니다. 사유: " + order.getCancelReason());
    }

    if (!order.isCancelable()) {
        return CancelOrderResult.notCancelable(order.getId(),
            order.getStatus().name(),
            "현재 상태(" + order.getStatus() + ")에서는 취소가 어렵습니다.");
    }

    order.cancel(command.cancelReason(), command.requestedBy());
    return CancelOrderResult.canceled(order.getId(),
        order.getStatus().name(),
        "주문이 취소되었습니다.");
}

// CancelOrderResult — Outcome enum 보유 (결함 3 해소)
public record CancelOrderResult(
    Long orderId, Outcome outcome, String orderStatus, String message
) {
    public enum Outcome {
        CANCELED, ALREADY_CANCELED, NOT_CANCELABLE, NOT_FOUND, NOT_OWNED
    }
    // factory 메서드들 ...
}
```

→ **`Order.cancel()` 메서드 내부의 `throw new IllegalStateException(...)`** 은 *defense-in-depth* 로 그대로 두기 — Service 가 isCancelable() 호출을 건너뛰는 *프로그래머 실수* 를 막는 안전망. Tool 진입점에서는 Outcome 으로 처리.

### 🟡 추가 결함 (우선순위 낮음)

| # | 결함 | 영향 |
|---|---|---|
| 4 | 로깅 부재 — Tool/Service 어디에도 `log.info` 없음 | Audit 불가, 사고 추적 어려움 |
| 5 | `CancelOrderToolResponse` vs `CancelOrderResult` 거의 동일한 record 중복 | DRY 위반, 필드 추가 시 두 곳 수정 |
| 6 | Tool description 에 customerId 안전 가이드 누락 (README 에는 명시) | system prompt 누락 환경에서 LLM 이 *사용자 메시지에서 customerId 추출* 시도 위험 |
| 7 | 동시성 보호 없음 — JPA + dirty checking 에 `@Lock`/`@Version` 없음 | 같은 주문 동시 Tool 호출 시 race condition (체크-액션 갭) |

---

## Phase 4-4 — 종합 + 메타 발견

### 핵심 발견

1. **Tool 호출 발동 시 토큰 비용 2.04배** (3,545 → 7,246) — 1차 컨텍스트 전체가 2차 호출에 재전송. Tool 호출 횟수가 N 늘면 *누적적* 증가.
2. **`PerformanceLoggingAdvisor (CallAdvisor)` 는 chain 외부 1회만 발동** — 1차/2차 LLM 호출 분리 측정 불가. Spring AI 1.0 의 chain 구조 한계.
3. **GPT-5.5 코드 의 *절반* 은 우리 라운드 가이드와 일치** (description 한국어 + 도메인 분리 + 본인 소유 검증). *프로덕션 운영의 디테일* 에서 결함 — 멱등성·예외·Outcome 구분.
4. **결함 3가지가 모두 우리 라운드 자산으로 개선 가능** — 1단계 Outcome enum 4종 + 2단계 멱등성 분기 패턴 + 4단계 로깅 advisor 그대로 적용.

### Round 1 vs Round 2 의 학습 흐름 종합

| 라운드 | 핵심 발견 |
|---|---|
| Round 1 1단계 | Structured Output schema 가 분류 anchoring. assertEquals 대신 분포 검증 |
| Round 1 2단계 | 메트릭이 *거짓 안심* 과 *거짓 경보* 둘 다. *raw 응답 인용* 이 진짜 가드 |
| Round 1 3단계 | Streaming = UX 축, 가드레일 별도 축 |
| Round 1 4단계 | 운영 비용의 95% 가 system prompt + advisor 누적 bug 발견 (*결함 발견 도구로서의 Observability*) |
| **Round 2 1단계** | Tool 호출률이 prompt·description 에 비단조 반응. **단일 prompt 통합** 이 분리보다 우월 |
| **Round 2 2단계** | 멱등성 분기 제거 → `canceledReason` 덮어쓰임 + 가짜 약속 LLM 응답. **AI 신뢰는 구조 신뢰** 재확인 |
| **Round 2 3단계** | description 의 *두 역할* 분리 — 호출 결정 vs 결과 해석. 거짓이라도 키워드 풍부함 > 빈약함 |
| **Round 2 4단계 (본 보고서)** | Tool 호출 발동 토큰 2배 + GPT-5.5 코드의 *멱등성 부재·예외 throw·boolean Outcome* 3 결함 |

### 운영 시사점 정리

| 자리 | 우리 적용 자산 |
|---|---|
| Tool 응답 표준 | `Outcome` enum 4~5종 (결함 3 대응) |
| 예외 처리 | 예외 throw → 결과 값 반환 (결함 2 대응) |
| 멱등성 | 별도 분기로 같은 응답 재전달 (결함 1 대응) |
| 로깅 | `[Tool] toolName(...)` advisor (결함 4 대응) |
| Tool description | 한국어 + Trigger Words + 입력 형식 + 실패 반환 4가지 (Round 2 3단계) |
| 동시성 | `@Version` 또는 비관적 락 (결함 7 — 후속 과제) |

### Round 3 (Memory) 연결 가설

본 라운드 발견 *"Tool 호출 시 1차 컨텍스트가 2차 호출에 재전송 → 비용 폭증"* 은 Round 3 의 **Chat Memory** 에서 더 큰 문제로 확장:
- N 턴 대화에 매 턴 *전체 컨텍스트* 재전송 → 토큰 비용 O(N²)
- → Memory Window·Summary 패턴 필요
- → Round 3 의 핵심 학습 자리

### 후속 과제

| 항목 | 내용 |
|---|---|
| chain 내부 advisor 도입 | 1차/2차 LLM 호출 분리 측정 → 정확한 비용 분석 |
| Tool 호출 횟수별 토큰 곡선 측정 | 1회/2회/3회/N회 Tool 호출 시나리오의 누적 토큰 |
| Round 1 Tool 등록 안 된 별도 client 추가 | quest 의도된 *Round 1 vs Round 2* 직접 비교 |
| GPT-5.5 결함 개선 코드를 *실제 동작 검증* | 본 보고서는 정적 분석. 동작 검증은 별도 PR 또는 후속 학습 |
| Tool description 토큰 vs 호출률 trade-off 곡선 | 본 라운드 3단계의 후속 — 어느 길이가 최적인지 정량 측정 |

---

## 부록 — 원본 코드 vs 우리 라운드 코드 1:1 비교

> GPT-5.5 원본 소스는 repo 에서 제외 (`.gitignore`). 본인 로컬에만 보관. 아래는 비교 요약.

| GPT-5.5 코드 | 우리 라운드 코드 | 차이 |
|---|---|---|
| `DeliveryOrderCancelTool.java` | `tool/OrderTools.java` (3 Tool 묶음) | 메서드 1개 vs 3개. 단일 Tool 분리는 향후 분리 기준 (Round 2 1단계 Q3) |
| `OrderCancelService.java` | (서비스 분리 안 함, Tool 메서드 안에서 처리) | GPT 가 *서비스 분리* 측면에서 더 클린, 우리는 *Outcome 분기* 측면에서 더 정확 |
| `Order.java` (JPA Entity) | `domain/Order.java` (Mock, 일반 클래스) | quest 명세가 Mock 요구. 실제 운영은 JPA 예상 |
| `CancelOrderResult.java` (boolean success) | `tool/CancelOrderResult.java` (Outcome enum) | **결함 3** 위치 |

→ **각 라운드의 학습 결과가 GPT 가 만들지 못하는 영역의 정답**. 인간 학습의 가치 직접 확인.
