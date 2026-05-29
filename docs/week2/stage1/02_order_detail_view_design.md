# OrderDetailView가 의도적으로 뺀 필드와 그 이유

`getOrderDetail` Tool이 LLM에게 돌려주는 [OrderDetailView](../../../src/main/java/com/baedal/support/tool/OrderDetailView.java)는 내부 도메인 모델 [Order](../../../src/main/java/com/baedal/support/domain/Order.java)의 모든 필드를 그대로 노출하지 않는다. 일부 필드는 의도적으로 제외했다.

---

## 1. 필드 매핑 비교

| Order 필드 | OrderDetailView | 비고 |
|---|---|---|
| `orderId` | ✓ orderId | |
| `storeName` | ✓ storeName | |
| `items` | ✓ items (Line으로 변환) | OrderItem 그대로가 아니라 Line(menuName/quantity/unitPrice)으로 평탄화 |
| `totalAmount` | ✓ totalAmount | |
| `orderedAt` | ✓ orderedAt | |
| `estimatedDeliveryAt` | ✓ estimatedDeliveryAt | |
| `deliveryAddress` | ❌ 제외 | |
| `riderLocation` | ❌ 제외 | |
| `status` (enum) | ✓ status (String) | enum → String 변환 |
| `canceledReason` | ❌ 제외 | |
| `canceledAt` | ❌ 제외 | |

총 4개 필드(`deliveryAddress`, `riderLocation`, `canceledReason`, `canceledAt`)가 제외됐다.

---

## 2. 필드별 제외 이유

### 2-1. `deliveryAddress` — 개인정보 보호

배달 주소는 고객의 실거주지 또는 직장 주소를 노출할 수 있는 민감 정보다.

- `getOrderDetail`은 "메뉴/금액/상태"를 묻는 일반 상담 흐름에서 호출된다. 본인 확인이 끝나지 않은 단계에서 주소가 LLM의 컨텍스트로 흘러들어가면, 다음 응답에 무심코 포함될 위험이 있다.
- 시스템 프롬프트의 "개인정보 노출 금지" 규칙으로 응답 단계에서 차단할 수도 있지만, **Tool 응답에서 아예 제거**하는 편이 안전하다. 모델이 받지 못한 데이터는 누설할 수 없다.

### 2-2. `riderLocation` — Tool 책임 분리

라이더 실시간 위치는 `getDeliveryStatus`가 반환하는 [DeliveryStatusView](../../../src/main/java/com/baedal/support/tool/DeliveryStatusView.java)의 책임이다.

- `getOrderDetail`은 "주문 그 자체"의 정적 정보(메뉴/금액/예상 도착 시간)를 다룬다. 라이더 좌표는 시시각각 변하는 동적 정보로 관심사가 다르다.
- 두 Tool의 응답이 겹치면 LLM이 어느 Tool을 불러야 할지 헷갈린다. **응답 모양을 명확히 분리하면 Tool 선택 정확도가 올라간다.**
- 개인정보 측면에서도 라이더의 이동 경로는 노출할 이유가 없다.

### 2-3. `canceledReason` / `canceledAt` — 취소 이력 노이즈

CANCELED 상태가 아닌 주문(대부분의 정상 흐름)에서는 두 필드 모두 `null`이다.

- 정상 주문에 대해 매번 `canceledReason=null, canceledAt=null`이 LLM 입력에 포함되는 것은 **노이즈**다. 모델이 "이 주문은 취소 사유가 없네?"라며 불필요한 추론을 할 수 있다.
- 취소된 주문의 사유는 고객이 입력한 자유 텍스트("결제 잘못함", "두 번 주문해서") — 다른 고객 응답에서 인용되면 개인정보 누설 위험. 별도로 마스킹·검토 없이 LLM에 흘려보내면 안 된다.
- 취소 이력은 별도의 흐름(`cancelOrder` Tool의 ALREADY_CANCELED outcome)에서 안내된다. `getOrderDetail`이 이중으로 노출할 필요가 없다.

---

## 3. 설계 원칙

`OrderDetailView` 클래스 javadoc에 두 가지 원칙이 명시되어 있다.

```java
/**
 * Tool 응답 DTO — LLM이 직접 읽는 구조이므로 필드명은 "LLM이 이해할 수 있는 자연어 키"로 둔다.
 * 내부 도메인 모델({@code Order})을 그대로 노출하지 않는다:
 * (1) 취소 이력/라이더 좌표 등 민감 정보를 필터링하기 위해
 * (2) LLM 입력 토큰을 줄이기 위해
 */
```

### (1) 민감 정보 필터링

도메인 모델은 시스템 내부에서 모든 데이터를 갖고 있어야 하지만, Tool 응답은 LLM 컨텍스트에 들어가는 순간 **로그·트레이스·다음 prompt 등 여러 곳에 복제**된다. 노출 범위가 훨씬 넓다.

### (2) 토큰 절약

LLM 입력 토큰은 비용·지연·정확도에 모두 영향을 준다.

- 토큰 수가 많아질수록 응답 시간이 늘어난다 (qwen2.5/llama3.1 모두 input ↑일수록 elapsed ↑).
- 모델 컨텍스트 윈도우는 유한하다. 멀티턴 대화에서 매번 불필요한 필드가 누적되면 더 빨리 한계에 도달한다.
- 무관한 필드가 많을수록 모델이 핵심 정보(메뉴/상태)에 집중하기 어려워 환각·오해석 확률이 올라간다 ([model_comparison.md](../stage1_old/02_model_comparison.md) 시나리오 2의 시간 환각 사례 참고).

---

## 4. 추가로 검토 가능한 점

- `status`를 enum → String으로 변환하는 이유는 enum 이름("DELIVERED", "COOKING")이 한국어 응답에서도 충분히 의미 전달이 되기 때문. 다국어 지원이 필요해지면 "배달완료", "조리중" 등 표시값을 매핑해 보내는 방향을 고려할 수 있다.
- `deliveryAddress`를 완전히 빼는 대신 **마스킹**해서 일부만 노출하는 옵션(예: "서울시 강남구 ***")도 있다. 본인 확인 후 별도 Tool로 노출하는 게 더 안전한 분리.
- `OrderItem`을 그대로 노출하지 않고 `Line` record로 다시 정의한 것 — 도메인 모델과 Tool DTO를 분리해 향후 OrderItem에 필드가 추가돼도 Tool 응답 형태는 안정적으로 유지된다.
