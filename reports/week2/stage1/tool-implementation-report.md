# 1단계 — Tool 구현 + 시나리오 5종 검증 보고서

> Round 2 / 1단계 — `OrderTools` 3개 + Mock 4건 + Tool 등록.
> **1차 시도 → 5차 시도 진화 과정 + prompt 통합 결정** 흐름 기록.

## 구현 요약

### 새 파일 (upstream/round2 import + 우리 구현)

| 파일 | 역할 |
|---|---|
| `domain/{Order, OrderItem, OrderStatus, OrderMockService}` | Mock 주문 도메인 (6건 — 1234~1239) |
| `tool/OrderTools` | `@Tool` 3개 — `getOrderDetail` / `getDeliveryStatus` / `cancelOrder` |
| `tool/{OrderDetailView, DeliveryStatusView, CancelOrderResult}` | Tool 반환 view (LLM 노출 전용 필드만) |
| `controller/AssistantController` | `POST /api/v1/assistant` — 자유 텍스트 + Tool Calling |

### Tool 등록 위치 (quest 명세 요구)

| 컨트롤러 | client | system prompt | Tool 등록 | 응답 형태 |
|---|---|---|---|---|
| `SupportController` → `SupportService` | `chatClient` | SYSTEM_PROMPT | ✅ | JSON 12필드 |
| `AssistantController` → `SupportService.chat()` | 동일 | 동일 | ✅ | 자유 텍스트 |
| `ChatController` / `StreamingChatController` | 동일 | 동일 | ✅ | 자유 텍스트 / SSE |

**Round 2 결정: 단일 ChatClient + 단일 prompt 통합** (1단계 분리 구조 합치기). 통합 결정 근거는 *prompt 통합 결정* 섹션 참고.

### Mock 데이터 (`OrderMockService.seed()` — 6건)

| orderId | 상태 | 매장 | 메뉴 | 용도 |
|---|---|---|---|---|
| 1234 | DELIVERING | 교촌치킨 강남점 | 허니콤보·콜라 | 배달 진행 + 라이더 위치 |
| 1235 | CREATED | 버거킹 선릉점 | 와퍼 세트 ×2 | 정상 CANCELED 경로 |
| 1236 | DELIVERED | 도미노피자 역삼점 | 페퍼로니 L·콜라 ×2 | NOT_CANCELABLE |
| 1237 | COOKING | 김밥천국 강남점 | 참치김밥 ×2·라면 | NOT_CANCELABLE 보조 |
| 1238 | CANCELED + `.cancel()` 호출 | BBQ치킨 강남점 | 황금올리브·콜라 | ALREADY_CANCELED 멱등성 |
| 1239 | ACCEPTED | 맥도날드 강남점 | 빅맥 세트·맥너겟 | 2단계 (B) 정상 취소 |

---

## 1차 시도 — Tool 호출이 발생하지 않음 ⚠️

### 결과

| # | 입력 | 기대 Tool | 실제 |
|---|---|---|---|
| S1 | 1234 배달 위치 | getDeliveryStatus | ❌ |
| S2 | 1234 메뉴 조회 | getOrderDetail | ❌ |
| S3 | 1235 취소 | cancelOrder | ❌ |
| S4 | 1236 NOT_CANCELABLE | cancelOrder | ❌ |
| S5 | 2099 NOT_FOUND | getDeliveryStatus | ❌ |

**모든 응답이 *"확인 후 안내드리겠습니다. 잠시만 기다려주세요"* 식 회피.** `[Tool] ...` 로그 0건.

### 원인 — `STREAMING_PROMPT` 의 예시가 회피 표현 학습

당시 `BaedalPrompt.STREAMING_PROMPT` 의 예시 1:
```
예 1) 고객: "주문번호 2024-1234 배달 어디쯤에 있어요?"
     → "주문번호 2024-1234 배송 상태를 확인한 뒤 안내드리겠습니다."   ← Tool 호출 없이 회피 응답
```

→ LLM 이 예시 패턴 그대로 학습 → Tool 호출 안 함.

quest 명세의 트러블슈팅 가이드 점검:
| # | 확인 항목 | 우리 상태 |
|---|---|---|
| 1 | `.defaultTools(orderTools)` 등록 | ✅ |
| 2 | **System Prompt 의 `[Tool 사용 규칙]` 섹션** | ❌ **없음** |
| 3 | 모델 `qwen2.5` | ✅ |
| 4 | 로그 레벨 `DEBUG` | ✅ |

**메타 발견**: Round 1 의 *안전한 어조* 가이드(*"확인 후 안내"*)가 Round 2 에서 **Tool 회피 부작용**으로 작용. *"prompt 의 한 표현이 라운드 컨텍스트에 따라 안전/결함을 오간다"*.

---

## 2차~4차 시도 — 부분 개선 + 회귀

### 시도별 변경 + 결과

| 시도 | 변경 | Tool 호출 케이스 | 비고 |
|---|---|---:|---|
| **2차** | `CORE_GUARDRAILS` 에 `[Tool 사용 규칙]` 추가 + STREAMING_PROMPT 예시 수정 | 3/5 (S1·S2·S5) | cancelOrder 회피 |
| **3차** | `[Tool 사용 규칙]` 에 cancelOrder 특별 가이드 추가 | 2/5 (S1·S2) | S5 회귀 (NOT_FOUND → hallucination) |
| **4차** | `cancelOrder` Tool description 강화 (★ 첫 줄로 호출 유도) | 2/5 (S2·S5) | S1 회귀 |

### 메타 패턴 — prompt 강화의 *비단조 효과*

```
1차: 0/5
2차: 3/5  ↑ 큰 개선
3차: 2/5  ↓ 회귀
4차: 2/5  → 평행 이동 (다른 시나리오 깨짐)
```

→ **prompt 한 곳 손볼 때 다른 시나리오가 깨질 수 있음**. *"prompt = 코드 가 아니라 prompt = 확률 분포 조정"*.

### 4차 시도의 실제 응답 인용 (S4 가짜 약속)

> 입력: `"주문번호 2024-1236 취소해주세요"` (1236 은 DELIVERED 상태)
> 응답: *"주문 번호 2024-1236의 주문을 확인하였습니다. 즉시 취소 요청을 처리하겠습니다. 잠시만 기다려주시기 바랍니다."*

→ 실제로는 DELIVERED 상태라 취소 불가. Tool 호출 안 했으니 `NOT_CANCELABLE` 신호 못 받음 → **존재하지 않는 처리를 약속하는 가짜 답변** (Round 1 [금지] 4번 위반).

---

## prompt 통합 결정 — 분리 → 단일

### 분리의 원래 동기 (Round 1)

1. `.stream()` 으로 JSON 응답 받으면 raw JSON 텍스트 노출
2. `customerMessage` 청자 분리

### 현재 시점에서 약화된 이유

1. **3단계 SSE 옵션 A** (token + meta 두 호출) 로 *streaming = 자유 텍스트 / meta = JSON* 이 호출 메서드에서 자동 분기 — prompt 분리의 의미 약함
2. **Round 2 발견** — `STREAMING_PROMPT` 의 회피 표현이 Tool 호출 방해

### 통합 후 구조

```java
public static final String SYSTEM_PROMPT = """
    [역할] [규칙] [금지] [Tool 사용 규칙]
    [분류 가이드]                ← (B) JSON 모드에서만 의미
    [응답 작성 가이드]
      ▸ (A) 자유 텍스트 모드 — .content()/.stream() 호출
        - 예시 4개 (위치 조회, 주문번호 요청, cancelOrder Outcome 4종, 보상 검토)
      ▸ (B) 구조화 모드 — .entity(SupportResponse.class) 호출
        - summary/customerMessage/nextAction 작성 가이드
    [정보 수집 가이드]
    [보상 처리 가이드]
    """;
```

호출 메서드별 자동 분기:
- `.content()` / `.stream()` → (A) 자유 텍스트
- `.entity(SupportResponse.class)` → Spring AI 가 JSON schema 첨부 → (B) 구조화

### `SupportService` 단일 ChatClient

```java
public SupportService(ChatClient.Builder builder, ...) {
    this.chatClient = builder
        .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
        .defaultAdvisors(performanceAdvisor)
        .defaultTools(orderTools)
        .build();
}
```

`AssistantController` 도 자체 캐싱 제거 → `SupportService.chat()` 호출.

---

## 5차 시도 — 단일 prompt 통합 후

### 결과

| # | 입력 | 기대 Tool | 실제 Tool 호출 | 응답 평가 |
|---|---|---|---|---|
| S1 | 1234 배달 위치 | getDeliveryStatus | ✅ | ✅ "역삼역 사거리" + ⚠️ "라이der" 한·영 혼용 |
| S2 | 1234 메뉴 | getOrderDetail | ✅ | ✅ 정확 (허니콤보 23000 + 콜라 3000 = 26000) |
| **S3** | 1235 취소 | cancelOrder | ❌ | 🔴 **새 실패** — JSON Tool call 텍스트 응답에 노출 |
| **S4** | 1236 NOT_CANCELABLE | cancelOrder | ✅ **첫 성공** | ✅ + ⚠️ 중국어 한 줄 섞임 |
| S5 | 2099 NOT_FOUND | getDeliveryStatus | ✅ | ✅ "찾을 수 없습니다" |

**Tool 호출 케이스: 4/5 (80%)**

### 5차의 새 실패 — S3 *"JSON Tool call 텍스트 누출"*

응답 본문:
```
주문 2024-1235를 취소해드리겠습니다. 주문 상태를 확인한 뒤 바로 처리하겠습니다.

{"name": "cancelOrder", "arguments": {"orderId": "2024-1235", "reason": "고객 요청"}}
```

`[Tool] cancelOrder(...)` 로그 없음. **LLM 이 Tool call JSON 을 응답 텍스트로 직접 작성** — Spring AI 의 Tool execution chain 미발동.

원인 추정: `qwen2.5` 가 Tool call 호출 결정은 했지만 출력 형식이 Spring AI 파싱 기대치와 어긋남. **quest 명세의 *"더 작은 모델은 Tool Calling 불안정"* 의 직접 사례**.

### 미세 결함 2건 — `qwen2.5` 다국어 모델 한계

| 케이스 | 응답 결함 |
|---|---|
| S1 | `"라이더"` → `"라이der"` (한·영 혼용) |
| S4 | 응답에 중국어 한 줄 `"接下来，您需要取消哪个订单呢?"` 섞임 |

→ Round 1 의 중국어 응답 패턴 변종 — `qwen2.5` 가 다국어 모델이라 *전체 한국어 100%* 보장 X.

### Tool 호출 시 토큰 비용

| 시도 | Tool 호출 평균 inputTokens |
|---|---:|
| 1차 (Tool 안 호출) | 1966 |
| 2차 | 4640~4709 |
| 5차 | **7215~7363 (3.6배)** |

→ prompt 통합 + Tool 호출 가이드 강화로 토큰 비용 3.6배. **운영 비용 측면에서 *"Tool 가이드의 깊이 vs 비용"* trade-off** 직접 측정.

---

## 진화 종합 표

| 시도 | 변경 | Tool 호출 | inputTokens (Tool 호출 시) | 핵심 한계 |
|---|---|---:|---:|---|
| 1차 | Tool 사용 규칙 없음 | 0/5 | (Tool 안 됨) | STREAMING_PROMPT 회피 표현 |
| 2차 | `[Tool 사용 규칙]` 추가 | 3/5 | 4640 | cancelOrder 회피 |
| 3차 | cancelOrder 가이드 추가 | 2/5 | (변동) | S5 회귀 |
| 4차 | cancelOrder description 강화 | 2/5 | 5349 | S1 회귀 + S4 가짜 약속 |
| **5차** | **단일 prompt 통합** | **4/5** | **7215** | S3 JSON 누출 (qwen2.5 한계) |

→ **단일 prompt 통합이 가장 효과적**. 1단계 자가 점검 *"각 시나리오 Tool 로그 캡처"* 5중 4 달성.

---

## 설계 결정 3가지 (quest 명세)

### Q1. `OrderDetailView` 가 내부 `Order` 의 무엇을 의도적으로 뺐는가?

`Order` 의 필드들과 `OrderDetailView` 의 필드를 비교:

| `Order` 필드 | `OrderDetailView` 포함? | 이유 |
|---|---|---|
| `orderId` | ✅ | 식별자 |
| `storeName` | ✅ | 고객 확인용 |
| `items` | ✅ (lines) | 메뉴 확인용 |
| `totalAmount` | ✅ | 결제 확인용 |
| `status` | ✅ | 상태 안내용 |
| `orderedAt` | ✅ | 주문 시점 |
| `estimatedDeliveryAt` | ✅ | 예상 도착 |
| **`deliveryAddress`** | ❌ | 고객 본인의 주소 — LLM 응답에 필요 없음. *[금지] 2번 - 다른 고객 주소 노출 방지* 적용 일관 |
| **`riderLocation`** | ❌ | `getDeliveryStatus` 도구 영역. 책임 분리 |
| **`canceledReason`** | ❌ | 취소 사유는 내부 정보 — `cancelOrder` Outcome 의 message 로만 표현 |
| **`canceledAt`** | ❌ | 위와 동일 |

→ **고객이 *주문 내용* 만 보면 충분한 정보** 만 포함. 운영·내부 정보(`canceledReason`, `riderLocation`) 는 별도 도구로 분리.
→ Round 1 `[금지]` 규칙의 *시스템 내부 ↔ 고객 노출 경계 분리* 와 일관.

### Q2. `@Tool` description 을 한국어 vs 영어, 어떤 기준으로?

**한국어 선택**.

기준:
- **모델·사용자 언어 일치 우선** — `qwen2.5` 는 한국어 입력 받음, 응답도 한국어. Tool description 도 같은 한국어가 의미 매칭 강함.
- **도메인 어휘** — *"배달 중"*, *"라이더 위치"*, *"취소 가능 여부"* 등 한국 배달 도메인 표현은 한국어가 자연스러움.
- **사용자(LLM) 가 본인 입장에서 읽음** — 영어 description 은 LLM 이 *번역 비용* 을 추가로 들임.

검증 — 5차에서 cancelOrder 호출 성공 (S4) 시 *"이미 배달 완료된 상태라서 취소할 수 없습니다"* 같은 자연스러운 한국어 응답 생성. description 의 한국어 어휘를 직접 가져옴.

다만 trade-off:
- LLM 학습 데이터의 영어 비중이 큰 모델 (예: GPT-4) 은 영어 description 이 더 정확할 수 있음 — *모델 의존 결정*.
- 토큰 효율 측면에서는 영어가 약간 유리 (`qwen2.5` 의 한국어 토큰화는 글자 단위로 잘게 쪼개짐).

### Q3. `OrderTools` 를 한 클래스로 묶은 이유 + 분리한다면?

**현재 한 클래스인 이유**:
- 3 Tool 모두 `OrderMockService` 의존 — 같은 데이터 소스
- 모두 *주문(Order)* 도메인 — 같은 책임 영역
- Round 2 수준에서 클래스 분리는 *과도한 추상화*

**향후 분리 기준** (실제 운영 규모일 때):

| 분리 축 | 예시 |
|---|---|
| **조회 vs 변경** | `OrderQueryTools` (getOrderDetail, getDeliveryStatus) vs `OrderCommandTools` (cancelOrder) — 변경 도구는 추가 권한·감사 로깅·트랜잭션 필요 |
| **도메인 단위** | `OrderTools` (주문/배송) vs `PaymentTools` (결제) vs `MenuTools` (메뉴) — 외부 시스템 연동이 다를 때 |
| **권한 수준** | `CustomerTools` (고객 본인) vs `AgentTools` (상담사) vs `AdminTools` (관리자) |

**현재 단계 정당화**:
- 3 메서드 분량 < 클래스 분리 비용
- *"YAGNI"* 원칙 — 운영 시 분리 기준이 명확해질 때 분리하는 게 더 정확

---

## 결론

| 측정 | 결과 |
|---|---|
| Tool 호출 성공률 | **4/5 (80%)** |
| 1단계 자가 점검 | 5중 4 통과. S3 `JSON Tool call 누출` 은 `qwen2.5` 한계 |
| 한국어 응답 안정성 | 3/5 완전 한국어, 2/5 미세 혼용 (한·영, 한·중) |
| **메타 발견** | (1) Round 1 가이드의 부작용이 Round 2 에서 노출됨 (2) prompt 강화가 비단조 — 한 곳 손보면 다른 곳 깨짐 (3) **단일 prompt 통합이 분리보다 우월** (5차 4/5) (4) `qwen2.5` Tool Calling 자체의 한계 (S3) |

### 다음 작업 (2단계 — 평가 핵심)

5차 결과의 **`cancelOrder` Tool 호출 4/5 만 성공** + S4 가 `NOT_CANCELABLE` 정상 처리됨 → 2단계 *멱등성 분기 제거 실험* 진행 가능.

S3 의 JSON 누출 패턴은 별도 관찰 — 2단계 진행 중 같은 입력 재호출하여 패턴 일관성 확인 예정.

### 후속 과제

| 항목 | 내용 |
|---|---|
| S3 패턴 안정성 | 같은 입력 반복 호출로 *JSON 누출* 비율 측정 |
| 한국어 응답 안정성 | `[규칙]` 1번 강화 또는 후처리 필터 검토 |
| 큰 모델 비교 | GPT-4 / Claude 등으로 Tool 호출 성공률 비교 (Round 2 4단계 자리) |
| 토큰 비용 최적화 | inputTokens 7215 → 운영 비용. prompt 분량 다이어트 또는 Tool description 압축 |
