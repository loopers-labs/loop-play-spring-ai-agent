# PR: Round 2 — Tool Calling으로 주문/배달 연동

## Round 2 — 완료한 단계

- [x] `@Tool` / `@ToolParam` 어노테이션으로 3개 Tool 노출 (`getOrderDetail`, `getDeliveryStatus`, `cancelOrder`)
- [x] 판단/실행 분리 (`OrderTools` ↔ `OrderMockService` ↔ `Order` 도메인)
- [x] `cancelOrder` 멱등성 분기 (`ALREADY_CANCELED`)
- [x] `CancelOrderResult.Outcome` 4분기 (CANCELED / ALREADY_CANCELED / NOT_CANCELABLE / NOT_FOUND)
- [x] View DTO 분리 (`OrderDetailView`, `DeliveryStatusView`) + 민감 필드 제외
- [x] System Prompt에 `[Tool 사용 규칙]` 섹션 추가
- [x] 양쪽 컨트롤러(`AssistantController`/`SupportController`)에 Tool 등록
- [x] `[Tool] xxx(orderId=...)` 감사 로그

Round 2 Review Guide 1차 자가 점검 후 발견된 **빌더 누적 함정(실수 11)**, **언어 일관성 위반**, **ToolParam 예시 함정(실수 9)** 3건을 추가로 수정했다.

---

## 핵심 설계 결정 3가지

**1. `CancelOrderResult.Outcome`을 4분기로 둔 이유**

`boolean success`나 `SUCCESS/FAIL` 2분기는 LLM이 고객에게 어떻게 안내할지 결정할 근거가 없다. 4분기는 각각 다른 고객 응답을 강제한다.

| Outcome | 고객 안내 |
|---|---|
| `CANCELED` | "취소되었습니다, 결제 환불은 N일 소요" |
| `ALREADY_CANCELED` | "이미 취소된 상태입니다" (재요청 안내) |
| `NOT_CANCELABLE` | "조리 시작으로 자동 취소 불가, 상담원 연결" |
| `NOT_FOUND` | "주문번호 다시 확인해주세요" |

- 2분기였다면: LLM이 "NOT_FOUND인데 NOT_CANCELABLE처럼 안내" → 고객이 다른 주문번호로 재시도할 기회 박탈
- 10분기 이상이었다면: qwen2.5에서 경계 케이스(`COOLING_OFF` vs `ALREADY_CANCELED`) 분류 일관성 깨짐. 1주차 `Category` 6분기 실험에서 이미 확인한 패턴

추가로 상상해본 분기: `REQUIRES_AGENT`(결제 이상으로 수동 처리), `COOLING_OFF`(방금 취소한 주문에 재취소). 둘 다 외부 시스템 연동 후에야 의미가 있어서 이번에는 보류.

**2. `OrderDetailView`에서 의도적으로 제외한 필드**

`Order` 엔티티 11개 필드 중 LLM 응답에서 5개를 제외했다.

| 제외 필드 | 이유 |
|---|---|
| `deliveryAddress` | 개인정보. 고객이 다시 자기 주소를 받을 이유 없음 |
| `riderLocation` | `getDeliveryStatus`에서 별도 노출 (응답마다 다른 스코프) |
| `canceledReason` | 내부 감사용. 고객 응답에 다시 노출되면 톤 충돌 |
| `canceledAt` | 동일 (감사 데이터) |
| `OrderItem.unitPrice` 외 내부 ID | 토큰 절약 |

7개 필드만 유지해서 입력 토큰은 줄이고, 엔티티 변경이 LLM 응답 스키마를 깨뜨리지 않게 격리했다.

**3. description 언어 정책: 모두 한국어로 통일**

처음에는 `cancelOrder`만 영문 description으로 두었다. *"영문이 LLM에 더 잘 인식된다"* 는 가정이었다. 그러나:

- `getOrderDetail` / `getDeliveryStatus` / `SYSTEM_PROMPT` 모두 한국어인데 한 Tool만 영문 → 다국어 모델(qwen2.5)에서 응답 톤 흔들림
- ToolParam에 `"Use '고객 요청' if not stated"` 같은 구체 예시를 박으면, 사유 발화가 없는 시나리오에서 LLM이 그 예시를 그대로 박아 호출 (Round 2 Review Guide 실수 9의 함정 그대로)

수정 후 전부 한국어 + 추상적 형식 (`"고객이 말한 취소 사유. 사유 발화가 없으면 빈 문자열을 전달한다."`)으로 통일.

---

## 가장 흥미로웠던 함정: Round 1 빌더 누적이 Round 2에서도 재발

1주차 README에 *"매 요청마다 `.build()`를 호출해도 괜찮다"* 고 결론을 적었었다. `OllamaApi` 같은 무거운 객체는 `ChatClient.Builder` 안에서 싱글톤이라는 이유였다. **이 결론이 Round 2에서 부분적으로 틀렸다.**

`AssistantController`는 새로 만들면서 가이드를 따라 생성자에서 한 번만 build하도록 작성했다. 그러나 `SupportController` / `PromptLabController` / `StreamingChatController`는 1주차 코드 그대로 두었고, **세 컨트롤러 모두 `@PostMapping` 메서드 안에서 `builder.defaultXxx(...).build()`를 호출**하고 있었다.

```java
// SupportController.java (수정 전) — Round 2 가이드 실수 11에 정확히 해당
@PostMapping
public SupportResponse triage(@Valid @RequestBody ChatRequest req) {
    return builder
            .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)   // ❌ 매 요청마다 누적
            .defaultAdvisors(performanceAdvisor)         // ❌ 매 요청마다 누적
            .build()
            .prompt().user(req.message()).tools(orderTools)
            .call().entity(SupportResponse.class);
}
```

`ChatClient.Builder`의 `.defaultSystem()` / `.defaultAdvisors()`는 stateful이다. Spring이 주입하는 빌더는 싱글톤이라 같은 컬렉션에 누적된다. 시간이 지나면 System Message가 N번 등록되어 입력 토큰이 폭증한다. `PromptLabController`는 더 위험했다 — 매 실험마다 *서로 다른* `req.systemPrompt()`가 누적되어 실험 결과 자체를 신뢰할 수 없게 된다.

**수정**:
- `SupportController`, `StreamingChatController`: 생성자에서 `ChatClient` 한 번 build, 메서드는 호출만
- `PromptLabController`: `systemPrompt`가 요청별로 달라서 `.defaultSystem()`을 못 쓰는 자리 → `.prompt().system(req.systemPrompt())`로 per-request 위임 (이쪽이 원래 의도였음)

1주차 README의 *"가벼운 설정 객체를 매번 만드는 건 괜찮다"* 결론은 옳지만, *"설정을 매 요청마다 누적시키는 건 다른 문제"* 라는 구분이 빠져 있었다. Round 2가 Round 1의 빈 자리를 정확히 찾아낸 셈.

---

## 결정적 검증 채널 — LLM 비결정성 우회

Round 2 Review Guide 실수 10이 강조한 *"LLM 호출률 변동 속에서 비즈니스 로직을 어떻게 검증하나"* 문제는, **`OrderToolsTest` 단위 테스트**로 해결했다. Admin endpoint를 따로 만드는 대신, Tool 메서드를 직접 호출해서 4분기 + 멱등성을 결정적으로 검증한다.

```java
// OrderToolsTest.java
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
```

두 번째 테스트가 가이드가 강조한 *"멱등성 분기 제거 시 `canceledReason`이 두 번째 사유로 덮어쓰임"* 시나리오의 결정적 검증이다. LLM이 cancelOrder를 0번 호출하든 5번 호출하든, 비즈니스 로직 자체의 멱등성은 이 테스트가 보장한다.

---

## 리뷰 요청 포인트

- `Order` 도메인이 가변(`status`, `canceledReason` setter 없는 대신 `cancel()` 메서드로 변경)인데, 진짜 동시 호출 시 `isCancelable()` 체크와 `cancel()` 사이 TOCTOU가 가능하다. 6주차 트랜잭션에서 다룰 예정인데, 이번 주차에 `synchronized` 정도라도 보강해야 할지 판단이 필요하다.
- `BaedalPrompt.SYSTEM_PROMPT`가 자유 텍스트(`AssistantController`)와 Structured Output(`SupportController`) 양쪽에서 공유된다. 1주차 README에 *"엔드포인트 목적에 따라 분리해야 한다"* 결론을 적었지만 2주차에 통합 그대로 두었다 (Round 2 Review Guide 실수 12). 통합 유지/분리 중 어느 쪽이 호출률·토큰에서 유리한지는 측정 안 했다.

---

## 변경 파일 목록 (10개)

| 파일 | 변경 내용 |
|------|---------|
| `OrderTools.java` | `cancelOrder` description/ToolParam 한국어 통일 + reason 예시 추상화 |
| `SupportController.java` | 빌더 누적 제거: 생성자에서 `ChatClient` 한 번 build, `.defaultTools(orderTools)` 등록 |
| `StreamingChatController.java` | 동일 패턴으로 리팩토링 + TODO 주석 정리 |
| `PromptLabController.java` | `.defaultSystem()` 누적 → `.prompt().system()` per-request 위임 |
| `AssistantController.java` | (기존) 생성자 패턴 + `.defaultTools(orderTools)` |
| `Order.java` | (기존) `cancel()` 도메인 메서드 + `isCancelable()` 판정 |
| `OrderMockService.java` | (기존) `ConcurrentHashMap` 시드 데이터, 6건 |
| `CancelOrderResult.java`, `OrderDetailView.java`, `DeliveryStatusView.java` | (기존) View DTO + Outcome enum |
| `BaedalPrompt.java` | (기존) `[Tool 사용 규칙]` 섹션 |
| `SupportControllerTest.java`, `PromptLabControllerTest.java` | 생성자 패턴에 맞춰 `@TestConfiguration` + `Answers.RETURNS_SELF` mock 빌더로 재작성 |

---

## 셀프 리뷰 — Round 2 Review Guide 필수 체크리스트

| 항목 | 결과 |
|---|---|
| `./gradlew bootRun` 정상 실행 + `OrderMockService seeded — 6건` 로그 | ✅ |
| `@Tool` + `@ToolParam` 사용 (3개 Tool) | ✅ |
| `@Tool` description 4요소 (무엇/언제/입력/실패) | ✅ (전 Tool 한국어로 통일) |
| 멱등성 (이미 취소된 주문 → `ALREADY_CANCELED`) | ✅ + 단위 테스트 2건 |
| View DTO 반환 (Order 엔티티 직접 노출 X) | ✅ |
| 실패를 Outcome enum으로 표현 (예외 throw 안 함) | ✅ |
| `OrderMockService` 별도 클래스 분리 | ✅ |
| System Prompt에 `[Tool 사용 규칙]` 섹션 | ✅ |
| `AssistantController` + `SupportController` 양쪽 `.defaultTools()` 등록 | ✅ (수정 완료) |
| Tool 진입 시점 `log.info("[Tool] ...")` | ✅ |
| 빌더 누적 함정 (Round 1 실수 1 = Round 2 실수 11 재발) | ✅ 모든 컨트롤러에서 제거 |
| description 언어 일관성 | ✅ 한국어로 통일 |
| ToolParam 예시 함정 (구체 사유 박힘) | ✅ 추상적 형식으로 변경 |
| 결정적 검증 채널 (단위 테스트로 Outcome 4분기 + 멱등성) | ✅ `OrderToolsTest` 14건 |
| `./gradlew test` 전체 통과 | ✅ 69개 테스트 통과 |

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)