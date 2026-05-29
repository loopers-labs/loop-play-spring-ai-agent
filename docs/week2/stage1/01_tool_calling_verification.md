# 1단계 검증 — Tool Calling 시나리오 5종

**목적** : Tool 호출이 정확히 일어나는지, 응답이 시나리오에 맞는지 검증하기 위해 
`/api/v1/assistant` 엔드포인트에 5개 시나리오를 호출하고 응답 본문과 Tool 호출 로그를 기록한다. 


## 구현 요약

### Tool 구현 : `OrderTools.java`

| Tool | 파라미터 | 반환 |
|------|---------|------|
| `getOrderDetail` | `orderId` | `OrderDetailView` \| null |
| `getDeliveryStatus` | `orderId` | `DeliveryStatusView` \| null |
| `cancelOrder` | `orderId`, `reason` | `CancelOrderResult` (Outcome 4분기) |

### Mock 데이터 : `OrderMockService.java` 

| 주문번호 | 상점 | 상태 | 용도 |
|---------|------|------|------|
| 2024-1234 | 교촌치킨 강남점 | DELIVERING | 배달 위치 / 메뉴 조회 |
| 2024-1235 | 버거킹 선릉점 | CREATED | cancelOrder → CANCELED 경로 |
| 2024-1236 | 맘스터치 역삼점 | DELIVERED | cancelOrder → NOT_CANCELABLE 경로 |
| 2024-1237 | 본죽 선릉점 | COOKING | NOT_CANCELABLE 경로 |
| 2024-1238 | 피자헛 강남점 | CANCELED | ALREADY_CANCELED 경로 (사전 cancel() 완료) |
| 2024-1239 | 이삭토스트 강남역점 | ACCEPTED | cancelOrder → CANCELED 경로 |

---

## **요청 형식** : 모든 시나리오 공통, 메시지만 교체 

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"<메시지>"}'
```

---

## 시나리오 1 — 배달 현황 조회

**요청**: `"주문번호 2024-1234 배달 어디쯤에 있어요?"`

**응답 본문**

> 현재 라이더는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다.

**Tool 호출 로그**

```
[Tool] getDeliveryStatus(orderId=2024-1234)
```

**결과**: ✅ Tool 호출: `getDeliveryStatus(2024-1234)`, ✅ 응답 내용: "역삼역 사거리" + "15분 후" 포함

---

## 시나리오 2 — 주문 메뉴 조회

**요청**: `"주문번호 2024-1234 어떤 메뉴 주문했어요?"`

**응답 본문**

> 주문하신 교촌치킨 강남점의 허니콤보 1개와 콜라 1.25L 1캔(총 26,000원)이 있습니다. 현재 배달 중이며, 예상 도착 시간은 약 37분 후입니다.

**Tool 호출 로그**

```
[Tool] getOrderDetail(orderId=2024-1234)
```

**결과**: ✅ Tool 호출: `getOrderDetail(2024-1234)`, ⚠️ 응답 내용: 메뉴·금액 정확하나 도착 시간 "약 37분 후"는 환각

**관찰**: **도착 시간 "약 37분 후"는 환각** — `estimatedDeliveryAt`가 절대시각으로만 전달되고 모델엔 현재시각 앵커가 없어, `orderedAt`과의 간격(≈35분)을 잘못 계산한 것으로 추정. 시나리오 1의 "15분 후"와 불일치.

---

## 시나리오 3 — 주문 취소 (CREATED 상태)

**요청**: `"주문번호 2024-1235 방금 시킨 건데 취소해주세요"`

**응답 본문**

> 주문이 성공적으로 취소되었습니다. 감사합니다.

**Tool 호출 로그**

```
[Tool] cancelOrder(orderId=2024-1235, reason=단순 변심)
```

**결과**: ✅ Tool 호출: `cancelOrder(2024-1235, reason=단순 변심)` → `Outcome.CANCELED`, ✅ 응답 내용: 취소 성공 안내

**관찰**: 사용자가 사유를 명시하지 않았으나 모델이 `단순 변심`으로 자동 추론해 호출. 취소 성공을 자연스럽게 안내. 이전 실험의 "취소 사유 재요청" 불일치 해소.성

---

## 시나리오 4 — 주문 취소 (NOT_CANCELABLE 상태)

**요청**: `"주문번호 2024-1236 취소해주세요"`

**응답 본문**

> 조리가 이미 시작되어 취소가 어렵습니다. 다른 방법을 찾는 중이니 잠시 기다려 주세요. 필요하다면 상담사에게 연결해 드릴 수도 있습니다.

**Tool 호출 로그**

```
[Tool] cancelOrder(orderId=2024-1236, reason=고객 요청)
```

**결과**: ✅ Tool 호출: `cancelOrder(2024-1236)` → `Outcome.NOT_CANCELABLE`, ⚠️ 응답 내용: 취소 불가 안내는 정확하나 사유 문구 부정확

**관찰**: Tool의 `NOT_CANCELABLE`을 정확히 취소 불가로 안내. 단, 2024-1236은 **DELIVERED(배달 완료)** 상태인데 응답은 "조리가 이미 시작되어"라고 표현 — `cancelOrder`의 `NOT_CANCELABLE` 메시지가 상태와 무관하게 "조리가 이미 시작되었습니다"로 고정돼 있어 발생한 문구 부정확. 결과(취소 불가)는 옳으나 사유 표현은 개선 여지가 있음(`OrderTools.cancelOrder` 메시지를 실제 상태에 맞게 분기 필요).

---

## 시나리오 5 — 존재하지 않는 주문 조회

**요청**: `"주문번호 2099-9999 배달 어디예요?"`

**응답 본문**

> 해당 주문번호를 찾을 수 없습니다. 다른 정보를 제공해주시면 감사하겠습니다.

**Tool 호출 로그**

```
[Tool] getDeliveryStatus(orderId=2099-9999)
```

**결과**: ✅ Tool 호출: `getDeliveryStatus(2099-9999)` → `null` 반환, ✅ 응답 내용: "찾을 수 없습니다" 안내

---

## 종합

**성공률**: Tool 호출 5/5 (100%) · 응답 품질 3/5 (시나리오 2 도착 시간 환각, 시나리오 4 사유 문구 부정확)

### 관찰
- **개선점 ① 시나리오 4 사유 문구**: `NOT_CANCELABLE` 메시지가 상태 무관하게 "조리가 이미 시작되었습니다"로 고정. DELIVERED 주문에는 부정확. `cancelOrder`의 메시지를 상태별로 분기하면 해소 가능.
- **개선점 ② 시나리오 2 도착 시간 환각**: `estimatedDeliveryAt`를 절대시각으로만 전달해 모델이 "약 N분 후"를 잘못 계산. 프롬프트에 현재시각을 주입하거나, DTO에서 "약 N분 후"를 미리 계산해 넘기면 해소 가능.

### 실행 정보
- **모델**: `qwen2.5`
- **프롬프트**: [assistant_system_prompt.md](/src/main/resources/prompts/assistant_system_prompt.md)
- **실행 스크립트**: [run_scenarios.sh](/docs/week2/stage1/toolcalling_verification/run_scenarios.sh) —  MODEL=qwen2.5  ./run_scenarios.sh
- **실행 시각**: 2026-05-24 13:26 (KST)
- **로그**: 스크립트가 Gradle 데몬 콘솔 출력에서 실행 구간 앱 로그만 추출해 `responses/qwen2.5/server_logs.log`에 저장