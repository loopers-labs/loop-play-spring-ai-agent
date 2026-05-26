# 1단계: Tool 3개 구현 + Mock 데이터 확장

**목표**: 세 개의 `@Tool`을 구현하고, 5종 시나리오로 호출이 정확히 분기되는지 검증한다.

## 검증 — 시나리오 5종


| # | 시나리오 | 기대 Tool | 기대 결과 |
|---|---|---|---|
| 1 | `"주문번호 2024-1234 배달 어디쯤에 있어요?"` | `getDeliveryStatus` | 라이더 위치 "역삼역 사거리" 포함 |
| 2 | `"주문번호 2024-1234 어떤 메뉴 주문했어요?"` | `getOrderDetail` | 허니콤보/콜라 포함 |
| 3 | `"주문번호 2024-1235 방금 시킨 건데 취소해주세요"` | `cancelOrder` | `CANCELED` |
| 4 | `"주문번호 2024-1236 취소해주세요"` | `cancelOrder` | `NOT_CANCELABLE` (DELIVERED 상태) |
| 5 | `"주문번호 2099-9999 배달 어디예요?"` | `getDeliveryStatus` (null 반환) | LLM이 "찾을 수 없다"고 안내 |

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}' | jq .
```

---

### 시나리오 1 — 배달 위치 조회

> ⚠️ 아래는 `BaedalPrompt.SYSTEM_PROMPT` 적용 시 결과 (시스템 프롬프트 변경 전).
> 단순 시스템 프롬프트로 변경 후 재테스트 결과는 아래에 덮어쓸 것. (회고 3 참조)

**응답 본문 (BaedalPrompt.SYSTEM_PROMPT 적용)**

```
배달원은 현재 역삼역 사거리 부근에서 배송 중입니다. 예상 도착 시간은 2026년 5월 23일 오후 6시 26분 38초로 추정됩니다.

추가로 궁금한 점이 있으신가요?

다음에 취할 행동은 기다리실 준비를 하시면 됩니다. 배달 시간이 변경될 수 있으니 정확한 도착 시간을 확인하시려면 다시 문의해 주세요.

estimatedResolutionMinutes: 0
customerSentiment: NEUTRAL
requiresHumanAgent: false
```

> Tool은 정상 실행됨 — "역삼역 사거리" 포함 확인. 단, SupportController용 응답 포맷(estimatedResolutionMinutes 등)이 혼재.

**콘솔 Tool 로그**

```
[Tool] getDeliveryStatus(orderId=2024-1234)
```

**응답 본문 (단순 시스템 프롬프트 적용 후)**

```
현재 라이더는 역삼역 사거리 부근에서 배달 중입니다. 예상 도착 시간은 2026년 5월 23일 오후 6시 37분 57초입니다.
```

---

### 시나리오 2 — 주문 메뉴 조회

**응답 본문**

```
주문하신 메뉴는 허니콤보 1개와 콜라 1.25L 1캔입니다. 총 금액은 26,000원이고 현재 배달 중인 상태입니다. 대략적인 도착 예정 시간은 오늘 오후 6시 37분입니다.
```

**콘솔 Tool 로그**

```
[Tool] getOrderDetail(orderId=2024-1234)
```

---

### 시나리오 3 — 정상 취소 (CREATED 상태)

**응답 본문**

```
주문번호 2024-1235의 주문이 성공적으로 취소되었습니다.
```

**콘솔 Tool 로그**

```
[Tool] cancelOrder(orderId=2024-1235, reason=고객 요청)
```

---

### 시나리오 4 — 취소 불가 (DELIVERED 상태)

**응답 본문**

```
죄송합니다, 주문번호 2024-1236의 경우 이미 배달이 완료된 상태로 조리가 시작되어 취소할 수 없습니다.
```

**콘솔 Tool 로그**

```
[Tool] cancelOrder(orderId=2024-1236, reason=고객 요청)
```

---

### 시나리오 5 — 존재하지 않는 주문번호

**응답 본문**

```
죄송합니다, 해당 주문번호로는 배달 정보를 확인할 수 없습니다. 주문번호를 다시 확인해 주세요.
```

**콘솔 Tool 로그**

```
[Tool] getDeliveryStatus(orderId=2099-9999)
```

---

## 회고

---

### 회고 1 — `ChatClient.Builder`는 공유 빈이다

`@RequiredArgsConstructor`로 `ChatClient.Builder`를 주입받고, 요청마다 `.defaultTools(orderTools).build()`를 호출했다. 첫 요청은 됐다. 두 번째 요청부터 터졌다.

```
IllegalStateException: Multiple tools with the same name
(getOrderDetail, getDeliveryStatus, cancelOrder) found in ToolCallingChatOptions
```

`ChatClient.Builder`가 요청마다 새로 생성되는 줄 알았다. 일반적인 스프링 빈처럼 DI로 주입받으면 매번 새로 만들어지는 게 아닐까 싶었는데, Spring이 싱글톤 빈으로 관리하는 공유 상태였다. `.defaultTools()`는 Builder 내부 목록에 누적 추가하는 방식이라, 요청이 올 때마다 같은 Tool이 하나씩 더 쌓인다.

생성자에서 `.build()`까지 끝내고 필드에 담아두는 방식으로 바꿨더니 해결됐다.

```java
public AssistantController(ChatClient.Builder builder, OrderTools orderTools) {
    this.chatClient = builder.defaultTools(orderTools).build(); // 시작 시 1회
}
```

---

### 회고 2 — Tool이 실행 안 되고 JSON 텍스트가 그대로 나왔다 (`num-ctx` 문제)

```
{"name": "getDeliveryStatus", "arguments": {"orderId": "2024-1234"}}
```

Tool 로그가 없었다. "역삼역 사거리" 대신 "[현재 위치]"가 나왔다. 코드를 다시 봐도 Tool 등록은 정상이었다. 뭐가 문제인지 감이 안 잡혔다.

그때 `PerformanceLoggingAdvisor` 로그를 봤다.

```
입력 토큰: 1917 | 출력 토큰: 131 | 총 토큰: 2048  ← 정확히 한계
```

Ollama 기본 `num-ctx`가 2048인데, 시스템 프롬프트 + Tool 정의 + 사용자 질문이 1917 토큰을 잡아먹었다. 남은 공간이 131 토큰이다. 총 토큰이 정확히 2048로 한계에 딱 걸렸으니, 모델이 Tool 호출 응답을 만들 공간이 없어서 JSON을 텍스트로 그냥 뱉은 것이다.

```yaml
spring:
  ai:
    ollama:
      chat:
        options:
          num-ctx: 8192
```

8192로 올렸더니 정상 동작했다. Observability가 없었다면 아마 한참 헤맸을 것이다. 토큰 수를 보는 것만으로도 이렇게 단서가 될 줄 몰랐다.

---

### 회고 3 — 단순 프롬프트가 더 나았던 또다른 예시

`AssistantController` 응답 말미에 이게 붙었다.

```
estimatedResolutionMinutes: 0
customerSentiment: NEUTRAL
requiresHumanAgent: false
```

Tool은 잘 실행됐고 "역삼역 사거리"도 있었다. 그런데 이 필드들이 따라왔다. 

`AssistantController`와 `SupportController`가 `BaedalPrompt.SYSTEM_PROMPT`를 공유하고 있었는데, 그 프롬프트 안에 `SupportController`용 JSON 포맷 명세(`estimatedResolutionMinutes` 등)가 포함돼 있었다. `AssistantController`는 Tool Calling 흐름을 자연어로 보려고 만든 엔드포인트인데, 거기에 구조화 출력 지시가 딸려온 것이다.

`AssistantController`에 단순 프롬프트를 따로 달았더니 해결됐다.

```java
.defaultSystem("당신은 배달 상담 AI입니다. 주문 조회, 배달 현황, 취소 요청을 처리합니다.")
```

시스템 프롬프트를 두 컨트롤러가 공유하면 안 된다는 걸 이번에 처음 체감했다. 목적이 다른 엔드포인트끼리 프롬프트를 나눠쓰면 이렇게 응답 포맷이 섞인다.

---

### 회고 4 — `cancelOrder` (2-파라미터 Tool)만 일관되게 실패했다 → 해결

시나리오 3을 실행하면 이게 나왔다. 2회 재시도해도 똑같았다.

```
portun
{"name": "cancelOrder", "arguments": {"orderId": "2024-1235", "reason": "고객 요청"}}
</tool_call>
```

Tool 로그가 없다. 실행 자체가 안 됐다.

| Tool | 파라미터 수 | 동작 |
|---|---|---|
| `getDeliveryStatus` | 1개 | 정상 실행 |
| `getOrderDetail` | 1개 | 정상 실행 |
| `cancelOrder` | 2개 | 텍스트 출력, 미실행 |

`</tool_call>` 태그가 그대로 노출된 걸 보면 Ollama가 tool call을 파싱하지 못한 것이다. `portun` 텍스트가 JSON 앞에 붙어 있다는 건, 모델이 tool call 앞에 한국어 텍스트를 먼저 뱉었고 그게 끊겨서 남은 것이다. Ollama 파서는 응답이 tool call로 시작해야 파싱에 성공하는 구조인데, 앞에 다른 텍스트가 오면 전체를 일반 텍스트로 넘겨버린다. 그래서 raw JSON이 그대로 출력된 것이다.

두 가지를 바꿨다.

**변경 1 — description 5줄 → 1줄**

```java
// 변경 전
@Tool(description = """
        주문을 취소합니다.
        취소 가능 조건: CREATED(주문 생성) 또는 ACCEPTED(사장님 수락) 상태인 경우에만 취소 가능합니다.
        취소 불가: COOKING(조리 시작) 이후 상태는 취소할 수 없습니다.
        멱등성: 이미 취소된 주문을 다시 취소 요청하면 에러가 아닌 ALREADY_CANCELED를 반환합니다.
        결과 타입: CancelOrderResult의 outcome 필드로 성공/실패 사유를 확인하세요.
        """)

// 변경 후
@Tool(description = "고객의 주문을 취소합니다. 취소 불가 상태이거나 존재하지 않는 주문이면 실패 이유를 반환합니다.")
```

**변경 2 — 시스템 프롬프트에 tool-first 지시 추가**

```java
.defaultSystem("당신은 배달 상담 AI입니다. 주문 조회, 배달 현황, 취소 요청을 처리합니다. 주문 관련 요청은 반드시 도구를 먼저 호출하여 처리하세요. 도구 호출 전에 다른 텍스트를 출력하지 마세요.")
```

결과: `[Tool] cancelOrder(orderId=2024-1235, reason=고객 요청)` 정상 실행.

두 변경을 동시에 했으니 어느 쪽이 결정적이었는지 분리해서 알 수는 없다. 다만 시나리오 4는 description이 긴 상태에서도 성공했던 걸 생각하면, 시스템 프롬프트의 tool-first 지시가 더 직접적인 영향을 줬을 가능성이 높다고 보고 있다. description 단축은 근본적으로 모델이 "뭔가 먼저 말하고 싶은 충동"을 줄이는 방향이고, 두 변경이 같은 문제를 다른 층에서 동시에 막은 셈이다.

---

## 설계 결정 문서

### `OrderDetailView`는 `Order`의 어떤 필드를 의도적으로 뺐는가?

`Order` 원본 필드 중 `OrderDetailView`에서 제외된 것들:

| 제외 필드 | 이유 |
|---|---|
| `deliveryAddress` | 고객 집 주소. LLM이 응답에 그대로 담을 수 있다 |
| `riderLocation` | `DeliveryStatusView`가 담당. 주문 상세 조회랑 관계없는 필드 |
| `canceledReason` | 취소 전 주문에서는 null이다. null을 넣어줄 이유가 없다 |
| `canceledAt` | 같은 이유 |

**왜 내부 도메인 모델을 그대로 LLM에 노출하지 않는가?**

LLM은 받은 것을 바탕으로 말한다. `Order`를 통째로 넘기면 `deliveryAddress`도 넘어가고, LLM이 그걸 응답에 그대로 담을 수 있다. 프롬프트로 막을 수 있다고 해도, 경계를 코드 구조로 강제하는 것과 프롬프트 지시에 의존하는 건 다른 이야기다.

그리고 이번 num-ctx 이슈를 겪으면서 불필요한 필드도 결국 토큰이라는 걸 직접 느꼈다. 1917 토큰에서 Tool 실행이 안 됐던 게 아직도 생생하다. LLM에 넘기는 정보는 그 Tool이 필요한 것만 최소로 줘야 한다.

`riderLocation`을 `OrderDetailView`가 아닌 `DeliveryStatusView`에 둔 건, 각 Tool이 담당하는 정보를 코드 구조로 구분하려는 의도였다. 어떤 Tool이 어떤 정보를 갖는지 명확해지면 LLM이 Tool을 잘못 고르는 상황도 줄일 수 있다고 생각한다.

---

### `@Tool`의 `description`을 한국어로 썼는가, 영어로 썼는가?

**선택**: 한국어

**기준**:

시스템 프롬프트도 한국어고 사용자 질문도 한국어니까, description도 같은 언어로 맞췄다. 컨텍스트 안에서 언어가 섞이면 모델이 처리하기 더 어려울 수 있다고 판단했다.

다만 이건 모델에 따라 달라지는 선택이다. GPT-4처럼 영어 학습 비중이 압도적인 모델이라면 다른 선택이 나을 수도 있다. qwen2.5는 한국어 처리가 되니 맞췄고, 실제로 잘 동작했다. 정답이 있는 문제가 아니라 사용하는 모델의 특성에 맞게 판단해야 한다.

---

### `OrderTools`를 하나의 클래스로 묶은 이유

세 Tool이 전부 같은 `OrderMockService`를 쓰고, 다루는 도메인도 "주문" 하나다. 지금 규모에서 굳이 파일을 나눌 이유를 못 찾겠다. 억지로 나누면 클래스만 늘어나고 오히려 전체 구조를 파악하기 불편해질 것 같았다.

**분리한다면 어떤 기준으로 나눌 것인가?**

| 분리 기준 | 클래스 A | 클래스 B |
|---|---|---|
| 조회 vs 변경 | `getOrderDetail`, `getDeliveryStatus` | `cancelOrder` |
| 주문 vs 결제 | 주문 관련 Tool | 결제 관련 Tool |

조회 vs 변경이 지금 맥락에서 더 자연스러운 기준이다. `cancelOrder`는 상태를 바꾸고 나머지 둘은 읽기만 한다. 실제 서비스라면 변경 Tool에만 권한 검사, 감사 로그, 트랜잭션이 붙는다. 그때 같은 클래스에 읽기 Tool이 섞여 있으면 불편해진다. 지금은 그 시점이 아니다. 주문 vs 결제 분리는 결제 Tool이 생길 때 고민할 문제다.

