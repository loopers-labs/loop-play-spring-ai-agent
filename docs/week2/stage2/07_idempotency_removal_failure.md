# 멱등성 분기 제거 실패 관찰

## 배경

[06_cancel_order_idempotency.md](06_cancel_order_idempotency.md)에서 `cancelOrder`의 멱등 흐름이 의도대로 작동함을 확인했다.
이번엔 **안전망을 빼고 어떤 사고가 노출되는지** 직접 관찰한다.

두 단계로 진행:
- **시나리오 A·B**: `ALREADY_CANCELED` 분기만 제거 (한 단계 안전망 제거)
- **시나리오 C**: `ALREADY_CANCELED` + `!isCancelable()` 가드 둘 다 제거 (모든 안전망 제거)

브랜치: `week2/feature01_v2_remove_idempotency`

---

## 1. 코드 변경

### A·B 단계 — ALREADY_CANCELED 분기만 제거

```diff
  var order = orderOpt.get();

- if (order.status() == OrderStatus.CANCELED) {
-     return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED,
-             "이미 취소된 주문입니다. 취소 사유: " + order.canceledReason());
- }

  if (!order.isCancelable()) {
      return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE,
              "현재 상태(" + order.status() + ")에서는 취소할 수 없습니다. 조리가 이미 시작되었습니다.");
  }
```

### C 단계 — `!isCancelable()` 가드까지 제거

```diff
- if (!order.isCancelable()) {
-     return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE,
-             "현재 상태(" + order.status() + ")에서는 취소할 수 없습니다. 조리가 이미 시작되었습니다.");
- }

  order.cancel(reason, LocalDateTime.now());
  return new CancelOrderResult(orderId, Outcome.CANCELED, "주문이 성공적으로 취소되었습니다.");
```

---

## 2. 시나리오 A — 첫 취소 (정상)

**요청**: "주문번호 2024-1239 취소해주세요"

**Tool 호출**: `[Tool] cancelOrder(orderId=2024-1239, reason=단순 변심)`

**LLM 응답**:
> 고객님, 주문번호 2024-1239의 주문이 정상적으로 취소되었습니다. 감사합니다.

기대대로 CANCELED outcome. 정상.

---

## 3. 시나리오 B — 같은 주문 재취소 (ALREADY_CANCELED 분기 제거 상태)

**요청**: "주문번호 2024-1239 진짜 취소됐어요? 한 번 더 취소해주세요"

**Tool 호출**: `[Tool] cancelOrder(orderId=2024-1239, reason=)` (reason 비어 있음)

**Tool 결과 메시지** (서버 내부 메시지):
> "현재 상태(CANCELED)에서는 취소할 수 없습니다. **조리가 이미 시작되었습니다.**"

**LLM 응답**:
> 고객님, 주문번호 2024-1239는 이미 취소된 상태입니다. 현재 상태로 인해 다시 한번 취소가 불가능합니다. 상담사에게 연결해 드릴까요?

### 핵심 관찰

1. **outcome이 `ALREADY_CANCELED` 대신 `NOT_CANCELABLE`로 빠짐** — `!isCancelable()` 가드가 두 번째 안전망으로 작동.
2. **Tool 메시지는 거짓**: 상태가 `CANCELED`인데 사유는 "조리가 이미 시작되었습니다"로 명시. `cancelOrder` 메시지 템플릿이 COOKING 상태를 가정하고 쓰였기 때문에 모순된 안내가 나옴.
3. **LLM이 의외로 모순을 흡수함**: 메시지의 `현재 상태(CANCELED)` 부분만 보고 "이미 취소된 상태입니다"로 정확히 안내 — 거짓 사유("조리가 이미 시작")는 응답에 누설되지 않았다. 다만 **불필요하게 상담사 연결을 제안**하는 escalation 발생.
4. **canceledReason 보존됨**: `cancel()`이 두 번째 호출에서 실행되지 않아 첫 호출의 reason("단순 변심") 유지.

→ 이 단계의 학습 포인트는 **`isCancelable()`이라는 두 번째 안전망의 존재 발견**. 멱등성 분기는 두 안전망 중 하나일 뿐이고, 다른 가드(상태 기반 검증)가 데이터 무결성을 지키고 있었다.

---

## 4. 시나리오 C — 두 안전망 모두 제거 + 동일 주문 2회 취소

서버 재시작으로 2024-1239 → ACCEPTED 상태로 복원 후 실행.

### C1 — 첫 취소

**요청**: "주문번호 2024-1239 잘못 주문했어요. 취소해주세요"

**Tool 호출**: `[Tool] cancelOrder(orderId=2024-1239, reason=잘못 주문)`

**LLM 응답**:
> 고객님, 주문번호 2024-1239의 주문이 정상적으로 취소되었습니다. 감사합니다.

→ 정상. `Order.cancel("잘못 주문", t1)`이 호출되어 `canceledReason="잘못 주문"`, `canceledAt=t1` 저장.

### C2 — 같은 주문 재취소

**요청**: "주문번호 2024-1239 그냥 변심해서 다시 취소해주세요"

**Tool 호출**: `[Tool] cancelOrder(orderId=2024-1239, reason=단순 변심)`

**LLM 응답**:
> 고객님, 주문번호 2024-1239의 주문이 정상적으로 취소되었습니다. 감사합니다.

### 핵심 관찰 — 영구 데이터 덮어쓰기

| 데이터 | 첫 호출 후 | 두 번째 호출 후 |
|---|---|---|
| status | CANCELED | CANCELED (변경 없음) |
| canceledReason | "잘못 주문" | **"단순 변심"** ← 덮어쓰임 |
| canceledAt | t1 (00:59:28) | **t2 (00:59:33)** ← 덮어쓰임 |
| outcome | CANCELED | **CANCELED** (또!) |
| LLM 응답 | "정상적으로 취소되었습니다" | **"정상적으로 취소되었습니다"** (똑같이) |

**사용자는 같은 주문을 두 번 취소했고 두 번 다 성공한 것으로 인지**. 시스템 내부에서는 원래 취소 사유와 시각이 영구 손실됐다.

서버 로그 증거:

```
00:59:28 [Tool] cancelOrder(orderId=2024-1239, reason=잘못 주문)
00:59:33 [Tool] cancelOrder(orderId=2024-1239, reason=단순 변심)
```

`Order.cancel()`이 두 번 실행됨 — 두 안전망이 모두 빠지면 cancel() 호출 자체에는 입력 검증이 없어 그대로 덮어쓰기.

---

## 5. 고객이 받을 수 있는 오해

### 시나리오 B에서 (한 안전망만 제거)

1. **"내 주문이 아직 조리 중인가?"** — Tool 메시지의 "조리가 이미 시작되었습니다"가 LLM 응답에 새어 나가는 변형 실행에선 고객이 음식 제조가 진행 중이라 오해할 수 있음 (이번 실행에선 다행히 LLM이 흡수했지만 비결정적).
2. **"상담사에게 연결해야 하나?"** — 단순 멱등 응답이어야 할 것이 escalation 제안으로 격상되어 고객 불안 증폭.
3. **"한 번 더 취소를 누르면 환불이 두 배인가?"** — 두 번 호출이 가능하다는 사실 자체가 고객에게 결제·환불 처리에 대한 혼란을 줌.

### 시나리오 C에서 (모든 안전망 제거)

1. **"두 번 취소되어서 환불도 두 번 처리되나?"** — 두 응답이 모두 "정상적으로 취소되었습니다"라 사용자가 두 차례 취소 처리됐다고 인지. 실제로는 같은 주문이지만 결제·환불 시스템에 중복 호출이 발생할 위험.
2. **"내가 적은 첫 번째 사유는 어디 갔나?"** — 운영팀이 "잘못 주문"으로 접수했다고 안내했는데 시스템엔 "단순 변심"으로 기록됨. 보상 정책(점주 책임 vs 고객 책임)이 달라질 수 있어 분쟁 시 신뢰 무너짐.
3. **"취소 시각이 왜 다른가?"** — 영수증/이메일에 기록된 첫 취소 시각과 시스템상 시각이 불일치. CS·법무 검증 시 고객이 거짓말한다고 의심받을 위험.
4. **"진짜 취소된 게 맞나?"** — 두 번 같은 응답을 받으면 첫 번째가 진짜였는지 의심하기 시작. 신뢰 저하.

---

## 6. 이 로직이 없었다면 프로덕션에서 발생했을 장애

### 6-1. 중복 환불 청구 / 환불 누락

`order.cancel()`이 두 번 호출되면 외부 결제 PG에 두 번 환불 이벤트가 발송될 수 있다.
- 멱등 키 없는 PG 연동에서 **고객에게 두 번 환불 → 회사 손실**, 또는 **첫 환불이 두 번째에 의해 취소 → 환불 누락**.
- 회계 결산 시 환불 차변/대변 불일치, 정산 보고서 오염.

### 6-2. 감사 추적·법적 증거 손상

`canceledReason`·`canceledAt`이 덮어쓰여 사라지면:
- 분쟁 시 "고객이 처음 어떤 사유로 취소했는지"를 시스템이 증명 못 함.
- 식품위생법·소비자보호법 분쟁에서 **사유는 보상 책임의 결정적 근거**. 시스템 로그가 분쟁 증거로 채택되지 않을 수 있음.
- 사고 발생 후 6개월 후 retrospective 분석 불가.

### 6-3. CS 폭주 + 응대 비용 증가

거짓·모순 응답("조리 중인데 취소가 안 됨", "두 번 다 정상 취소") 때문에:
- 고객이 즉시 인입콜·인입챗으로 escalation
- CS 상담사는 "왜 두 번 응답이 같죠?", "조리 중이면 언제 환불되나요?"에 답할 수 없어 추가 escalation
- 1건의 시스템 결함이 N건의 CS 티켓으로 증폭

### 6-4. 외부 시스템 신호 오염

POS·배차·재고·라이더 앱 등이 cancel 이벤트를 구독하고 있다면:
- 두 번째 cancel 이벤트가 전파되어 **이미 취소된 주문을 다시 취소 처리**하려다 각자 시스템에서 예외 발생
- 일부는 graceful하게 무시하지만, 입력 검증이 약한 시스템에선 라이더 배차 시퀀스가 꼬이거나 재고 복원이 두 번 일어남
- 분산 시스템 동기화 어그러져 데이터 정합성 회복 비용 큼

### 6-5. 취소 사유 분석의 통계 오염

운영팀이 "어떤 사유의 취소가 가장 많은가"를 분석할 때:
- 마지막 cancel() 호출의 reason만 남기 때문에 같은 주문이 여러 사유로 취소된 경우 첫 사유들이 통계에서 사라짐
- "단순 변심" 통계가 부풀려지고 "잘못 주문"(점주 책임) 통계가 줄어들어 정책 결정이 잘못된 방향으로 흐를 수 있음

---

## 7. 결론

### 멱등성 분기의 진짜 가치

`ALREADY_CANCELED` 분기는 "예외 던지지 않기" 정도가 아니라 **상태에 맞는 정확한 메시지로 고객을 안내하는 안전망**:

| 안전망 | 역할 |
|---|---|
| `ALREADY_CANCELED` 분기 (1차) | 이미 취소된 상태를 정확히 인식해 "이미 취소된 주문입니다" 메시지 전달 |
| `!isCancelable()` 가드 (2차) | ALREADY_CANCELED를 빠뜨려도 마지막에 NOT_CANCELABLE로 막아 cancel()이 두 번 호출되지 않게 보호 |
| `Order.cancel()` 내부 검증 (없음) | 현재는 입력 검증 없이 그대로 필드 덮어쓰기. 두 안전망이 무너지면 그대로 사고. |

### 도메인 모델에 추가 검증을 둘 것인가?

이 실험은 **`Order.cancel()` 자체에 멱등성 검증을 두는 것이 더 안전**함을 시사한다:

```java
public void cancel(String reason, LocalDateTime at) {
    if (this.status == OrderStatus.CANCELED) {
        return; // 또는 IllegalStateException
    }
    this.status = OrderStatus.CANCELED;
    this.canceledReason = reason;
    this.canceledAt = at;
}
```

이렇게 두면 cancelOrder Tool의 분기가 빠지더라도 도메인 모델이 마지막 방어선이 된다. **계층마다 자기 책임의 invariant를 지키는 것**이 안전망 누적 효과를 만든다.

### 시나리오 B의 또 다른 교훈 — 거짓 메시지 위험

`NOT_CANCELABLE` 메시지가 "조리가 이미 시작되었습니다"로 하드코딩되어 있어, CANCELED 상태에서 잘못 분기되면 **상태와 사유가 모순**된다. LLM이 이번엔 흡수했지만, 다른 LLM·온도·메시지 변형에선 거짓 정보가 그대로 새어 나갈 위험. 메시지 템플릿은 **상태별로 구성**하거나 결과 객체에 `status`·`reason`을 분리 제공하는 것이 안전.

---

## 8. 산출물 경로

```
docs/week2/stage1/responses/idempotency_removed/
├── scenarioA.json                    # 첫 취소 (CANCELED)
├── scenarioB.json                    # 재취소 — NOT_CANCELABLE 빠짐
├── server_logs.txt                   # A·B 단계 Tool 로그
└── scenarioC/
    ├── C1.json                       # 첫 취소 (reason=잘못 주문)
    ├── C2.json                       # 재취소 (reason=단순 변심, 덮어쓰기)
    └── server_logs.txt               # C 단계 Tool 로그 (덮어쓰기 증거)
```
