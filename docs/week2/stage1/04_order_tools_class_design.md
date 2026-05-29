# OrderTools 클래스 설계 — 통합 vs 분리 (요점)

[OrderTools](../../../src/main/java/com/baedal/support/tool/OrderTools.java)는 조회 2개·변경 1개, 총 3개의 `@Tool` 메서드를 한 클래스에 묶고 있다. 성격이 다른 작업이 섞여 있어 분리를 검토할 수 있다. 이 문서는 분리 기준 후보를 비교하고 **현재 통합이 적절한 이유**를 요약한다.

---

## 1. 분리 기준 후보 — 두 축으로 나눠 본다

분리를 논할 땐 두 질문이 섞이기 쉽다. **(A) 어떤 선으로 묶나**(묶는 기준)와 **(B) 무엇을 얻으려고 묶나**(목적)는 서로 다른 축이다.<br>
"조회 vs 변경"이나 "도메인별"은 *묶는 기준*이고, 그게 권한(보안)
을 위한 것일 수도 토큰 효율을 위한 것일 수도 있다. 

### (A) 어떻게 묶나 — 묶는 기준

| 묶는 기준 | 선 긋는 방식 | 분리 형태 예 |
|---|---|---|
| 연산별 | 조회 vs 변경 (CQRS) | `OrderQueryTools` / `OrderCommandTools` |
| 도메인별 | 엔티티 경계 | `OrderTools` / `PaymentTools` / `ReviewTools` |
| 권한별 | 인증 요구 수준 | `PublicOrderTools` / `AuthOrderTools` |
| 사용패턴별 | 호출 빈도·문맥 | `HotPathOrderTools` / `RareOrderTools` |

### (B) 무엇을 위해 — 분리의 목적

| 목적 | 얻는 것 | 적합한 시점 |
|---|---|---|
| **보안 경계 (권한)** | 본인 확인을 클래스 단위로 강제 | 본인 확인을 advisor/Security로 강제하기 시작할 때 |
| **토큰·지연 절감** | description 토큰 축소, 노이즈 제거 | Tool이 10+ 개로 늘어 description이 input의 30%↑일 때 |

---

## 2. 분리를 적용한다면 — 보안·토큰 구현과 런타임 흐름

### 2-1. 목적 :  토큰 효율 — 사용패턴별 묶기 (호출 빈도·문맥)
> **요약**: Tool 설명은 매 요청마다 input 토큰을 먹는다 <br>
> → Tool이 많아지면 안 쓰는 것까지 다 보내는 게 낭비 <br>
> → 흐름별 ChatClient에 필요한 Tool만 등록하면 토큰·지연·정확도가 개선된다. <br>
> 단, 충분한 사용 데이터가 쌓인 뒤에만 가능한 최적화다.

#### 1) 전제: tool description은 매 요청마다 LLM에 전송된다

`@Tool`을 ChatClient에 등록하면 Spring AI는 그 Tool의 **이름 + 파라미터 + description 텍스트**를 LLM 입력(input)에 매번 끼워 넣는다. LLM이 "어떤 Tool이 있고 언제 부르는지" 알아야 하기 때문이다.

```
[시스템 프롬프트]
[대화 내역]
[사용 가능한 Tool 목록]   ← 매 요청마다 전송됨
  - getOrderDetail(orderId): "주문의 메뉴·금액·상태를 조회. 고객이..."   (예: 80 토큰)
  - getDeliveryStatus(orderId): "라이더 실시간 위치와 예상 도착..."        (예: 70 토큰)
  - cancelOrder(orderId, reason): "주문을 취소. CANCELED/ALREADY..."     (예: 90 토큰)
```

Tool이 3개면 부담이 없지만 **10~20개로 늘면** description만으로 input의 큰 비중을 차지한다. 게다가 이건 그 Tool을 이번 턴에 쓰든 안 쓰든 **항상** 전송된다.

#### 2) 문제: 안 쓰는 Tool도 항상 끼어든다

- **비용·지연**: input 토큰이 많을수록 요금이 오르고 응답이 느려진다. (input ↑일수록 elapsed ↑ — [02_order_detail_view_design.md](02_order_detail_view_design.md) 참고)
- **정확도**: 지금 상황과 무관한 Tool 설명이 섞이면 모델이 핵심에 집중하기 어렵고, 엉뚱한 Tool을 고를 확률이 올라간다(노이즈).

예: "내 주문 어디쯤 왔어?" 흐름에선 `getDeliveryStatus`만 필요한데, `cancelOrder`의 긴 description까지 매번 같이 전송되는 게 낭비다.

#### 3) 해결의 핵심: 절감을 만드는 건 "등록 범위(scope) 축소"다

**flat list**: 클래스/파일을 나누면 토큰이 준다? **아니다.** Spring AI는 등록된 `@Tool` 메서드를 **클래스 구분 없이 flat list로** 펼쳐 보낸다.

```java
// 클래스는 2개로 나눴지만, 둘 다 등록 → 전부 직렬화. 절감 0.
builder.defaultTools(hotPathOrderTools, rareOrderTools).build();
```

토큰이 줄어드는 건 **한 ChatClient가 일부 Tool만 등록할 때**다. 

```java
@Component class HotPathOrderTools { ... }  // getOrderDetail, getDeliveryStatus
@Component class RareOrderTools    { ... }  // cancelOrder 등 호출 빈도 낮은 작업

// 핫패스 흐름(조회 위주)에는 hot Tool만 "등록" → rare description은 아예 안 실림
this.hotChatClient  = builder.defaultTools(hotPathOrderTools).build();
// → getOrderDetail, getDeliveryStatus 의 description만 input에 들어감
//   cancelOrder 의 90토큰은 이 흐름에선 빠짐

// 변경 작업이 필요한 흐름에서만 전체를 등록한 ChatClient를 쓴다
this.fullChatClient = builder.defaultTools(hotPathOrderTools, rareOrderTools).build();
```

- **장점**: context window의 tool description 토큰을 줄여 비용·지연 절감, 안 쓰는 Tool 노이즈 제거로 모델 정확도 향상.
- **단점**: 가장 위험한 건 **잘못 분류**다. 어떤 흐름에서 정작 필요한 Tool을 그 ChatClient에서 빼면, LLM은 그 Tool의 존재 자체를 모르므로 **호출을 시도조차 못 한다**(silent failure).


### 2-3. 스코프를 나누면 런타임 흐름은? — 라우터와 ChatClient 교체

>**요약** : 같은 엔드포인트 내부에서 ChatClient(또는 per-request tools)만 교체. <br>
> 단 **LLM 앞단에 라우터가 추가**되고, **메모리 공유**가 필수이며, **조회/변경 경계 발화 오분류 = silent failure** 위험이 생긴다. <br>
> 이 라우팅 비용·위험이 바로 Tool 3개에선 스코프 분리가 손해이고 통합이 유리한 이유다 — 토큰 절감(이득)보다 라우터·메모리·오분류(비용)가 더 크다. 

스코프를 조회/변경으로 나눴다고 하자. 고객이 조회 위주로 묻다가 "취소 가능 여부"를 묻고, 결국 "취소해줘"(변경)로 넘어가면 코드 흐름이 어떻게 될까?

**핵심: 새 controller를 호출하는 게 아니다.** 클라이언트는 같은 엔드포인트(`/api/v1/assistant`)를 계속 호출하고, **서버 내부에서 어떤 ChatClient bean을 쓸지만** 바뀐다.

#### 1) ChatClient는 LLM이 메시지를 보기 *전에* 골라야 한다

조회용 ChatClient에는 `cancelOrder`가 **등록조차 안 돼 있다.** 그래서 고객이 "취소해줘"라고 했을 때, LLM이 그 메시지를 읽고 "cancelOrder를 불러야지" 판단하려 해도 → **그 ChatClient엔 그 Tool이 없어서 부를 수가 없다.** "변경 의도"는 메시지를 이해해야 알 수 있는데, 어떤 ChatClient를 쓸지는 그 LLM 턴이 시작되기 **전에** 정해야 한다. 이걸 풀려면 LLM 앞단에 **라우터(의도 분류기)**가 하나 더 필요하다.

```
매 턴: Client → POST /api/v1/assistant   (controller·엔드포인트는 항상 동일)
                    │
                    ▼
              AssistantService
                    │  ① 라우터가 먼저 의도 분류 (키워드/소형 LLM/분류기)
                    │     intent = QUERY or COMMAND
                    │  ② 의도에 맞는 ChatClient bean 선택
                    ▼
        ┌───────────────────────────┐
        │ queryChatClient            │  getOrderDetail, getDeliveryStatus
        │ fullChatClient             │  + cancelOrder
        └───────────────────────────┘
                    │  ③ 선택된 client로 .prompt().user(msg)
                    │     .advisors(공유 ChatMemory)  ← 핵심
                    ▼
                  LLM 호출
```

| 턴 | 고객 발화 | 라우터 판정 | 사용 ChatClient |
|---|---|---|---|
| 1 | "내 주문 어디야?" | QUERY | queryChatClient |
| 2 | "이거 취소 돼?" | **애매** ⚠️ | ? |
| 3 | "취소해줘" | COMMAND | fullChatClient |

#### 2) 반드시 공유해야 하는 것 — 대화 메모리

ChatClient를 바꿔도 **대화 history(ChatMemory)는 두 client가 공유**해야 한다. 안 그러면 3턴에서 fullChatClient로 넘어가는 순간 1~2턴 맥락("어떤 주문 얘기 중이었는지")이 날아간다.

```java
// 두 ChatClient는 "Tool 묶음 + 설정"만 다를 뿐, 같은 ChatMemory를 공유
this.queryChatClient = builder.defaultTools(orderQueryTools)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(sharedChatMemory).build())
        .build();
this.fullChatClient  = builder.defaultTools(orderQueryTools, orderCommandTools)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(sharedChatMemory).build())
        .build();
// 같은 conversationId로 호출하면 history가 이어진다
```

즉 ChatClient는 **갈아끼우는 부품**이고, 메모리는 **고정**이다.

#### 3) 위험 지점은 정확히 2턴 "취소 가능 여부 조회"

이게 조회/변경 경계에 걸쳐 있어 오분류하기 쉽다.

- "취소 돼?"를 **QUERY로 보면** → queryChatClient엔 `cancelOrder`가 없음 → 가능 여부를 정확히 답하려면 `cancelOrder`의 `NOT_CANCELABLE` outcome이 필요한데 못 부름 → status만 보고 LLM이 추측 → **틀릴 수 있음**.
- "취소 돼?"를 **COMMAND로 올려보면** → 실제로 취소를 실행해버릴 위험(조회만 원했는데).

라우터가 여기서 틀리면 **silent failure**다. LLM은 없는 Tool의 존재 자체를 모르니 "못 부른다"는 신호조차 안 남긴다.

#### 4) 그래서 현실적 대안 2가지

1. **Sticky upgrade(점착 승격)**: 한 번 COMMAND 의도가 감지되면 그 세션은 그 뒤로 계속 fullChatClient를 쓴다. 라우터를 매 턴 돌리지 않아 단순하고 경계 오분류 위험이 준다. 대신 토큰 절감 효과는 "승격 전"에만 발생.
2. **요청마다 tools 동적 지정**: ChatClient bean을 둘로 나누지 말고 **하나**만 두고, 라우터 판정에 따라 `.prompt().tools(...)`로 그 턴에 쓸 Tool 부분집합만 넘긴다. (메모리 공유 문제가 자연히 사라짐)

```java
var tools = router.isCommand(msg)
        ? List.of(orderQueryTools, orderCommandTools)
        : List.of(orderQueryTools);
chatClient.prompt().user(msg).tools(tools.toArray()).call();
```


---

## 3. 현재(통합) 구조가 적절한 이유

| 이유 | 설명 |
|---|---|
| Tool 수가 적다 (3개) | 분리는 5~10개↑부터 가치. 지금은 한 클래스로 한눈에 보는 게 빠름 |
| 단일 도메인 | 세 메서드 모두 `Order` 엔티티를 다룸 → 같이 변경됨 |
| 공통 의존성 | 모두 `OrderMockService` 하나에 의존. 의존성이 같으면 같은 클래스 신호 |
| 테스트 단순성 | `OrderToolsTest` 한 곳에서 분기 관리, fixture·mock 중복 없음 |
| 등록 단순성 | `defaultTools(orderTools)` 한 줄 → 누락(silent failure) 위험 없음 |
| 변경 빈도 동일 | description·에러 정책·로그 포맷이 세 메서드에 함께 적용됨 |
| LLM은 클래스를 모름 | Tool 라우팅은 `@Tool` **메서드** 단위. 클래스 분리는 사람 편의일 뿐 LLM 선택과 무관 |

---

## 4. 권장

**현재는 통합 유지.** 단, `OrderTools.java` javadoc에 분리 검토 트리거를 명시해 두면 미래 의사결정에 도움이 된다 — 다음 중 2개 이상 충족 시 분리 고려:

- Tool 수 ≥ 7
- 새 도메인 Tool 추가 (`Order` 외 엔티티 의존)
- 변경 작업에만 `@Transactional`/감사/권한 advisor 적용 필요 → **권한 레벨 분리**
- 팀 오너십 분리
- Test fixture 비대화 (30+ 분기)
- Tool description 토큰이 LLM input의 30%↑ → **토큰 효율 분리**
