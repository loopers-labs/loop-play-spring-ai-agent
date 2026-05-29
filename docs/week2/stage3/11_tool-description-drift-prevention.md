# @Tool description 오염, 프로덕션에서 어떻게 막을 것인가

## TD;LR
파라미터 이름·타입은 Spring AI가 알아서 막아준다.
설명 글의 거짓말은 계약 테스트로 막고, 그래도 새면 모니터링으로 잡는다.


| 단계 | 무엇을 막나 | 우리가 할 일 |
|---|---|---|
| 1. 자동 추출 | 파라미터 이름·타입 오염 | `@Tool` 쓰는 것만으로 끝 (이미 됨) |
| **2. 계약 테스트** | **설명 글 ↔ 코드 동작 불일치** | **약속마다 테스트 작성 → GitHub Actions로 자동 실행 → 통과해야 머지** |
| 3. 모니터링 | 그래도 새는 것 | 호출 실패·재시도 로그 관찰 |

---

## 1. 먼저, 문제가 뭔지

우리는 `@Tool` / `@ToolParam`을 써서 LLM에게 도구를 설명한다.

```java
@Tool(description = """
        배달 상태와 라이더 위치를 조회한다.
        고객이 배달 현황·도착 시간을 물을 때 호출한다.
        존재하지 않는 주문번호면 null을 반환한다.
        """)
public DeliveryStatusView getDeliveryStatus(
        @ToolParam(description = "조회할 주문번호 (예: 2024-1234)") String orderId) {
    ...
}
```

이 description 안에 **두 종류의 정보**가 섞여 있는데 각각 오염되는 경로가 다르다.

| 부분 | 누가 만드나 | 오염되나?                        |
|---|---|------------------------------|
| `orderId`, `String` 같은 **파라미터 이름·타입** | Spring AI가 코드에서 자동 추출 | **오염 안 됨** (코드 고치면 같이 바뀜)    |
| `"배달 상태와 라이더 위치를 조회한다."` 같은 **설명 글** | 사람이 손으로 씀 | **오염 됨** (코드만 바뀌고 글은 옛날 그대로) |

---

## 2. 그래서 어떻게 막나 — 세 단계

### 1단계. 파라미터 이름·타입은 이미 안전하다

`@Tool`을 쓰면 파라미터 이름·타입은 Spring AI가 코드에서 자동 추출한다. `orderId`를 바꾸면 LLM에게 가는 설명도 같이 바뀌므로 구조적으로 오염되지 않는다. 남은 문제는 **설명 글**뿐 — 2·3단계가 그걸 막는다.

### 2단계. 계약 테스트 — 설명 글이 사실인지 코드로 검증 (핵심)

description이 한 약속을 실제로 호출해 확인하는 테스트를 작성한다. description의 문장 하나하나를 이렇게 박아두는 게 목표다.

```java
// description: "존재하지 않는 주문번호면 null을 반환한다"
@Test
void 존재하지_않는_주문번호면_null을_반환한다() {
    DeliveryStatusView result = deliveryTools.getDeliveryStatus("0000-0000");
    assertThat(result).isNull();   // 약속이 사실인지 검증
}
```

```java
// description: "배달 중인 주문에만 라이더 위치가 유효하다"
@Test
void 배달완료_주문은_라이더위치가_없다() {
    DeliveryStatusView result = deliveryTools.getDeliveryStatus("2024-9999"); // 배달완료 주문
    assertThat(result.riderLocation()).isNull();
}
```

누군가 코드를 "null 대신 예외"로 바꾸면 이 테스트가 깨져, 머지 전에 설명↔코드 불일치가 드러난다.

### 2단계(2) GitHub Actions로 구현↔테스트 동반 수정을 강제한다

push마다 GitHub가 자동으로 돌린다. (참고 [ci.yml](../../../.github/workflows/co-change-check.yml))

1. 테스트를 강제 실행
2. **동반 수정을 강제** : 구현이 바뀌면 테스트와 description도 같이 바뀌어야 한다는 규칙을 검사
3. 테스트 종류 :
    - OrderToolsSelectionEvalTest : LLM **Tool 선택 정확도** 테스트 (맞는 도구를 80% 이상 고르는가)
    - OrderToolsTest : Tool 단위 테스트로 **설명 ↔ 코드 일치 계약 테스트** (없는 주문번호 null반환 등)

### 3단계. 모니터링 — 그래도 새면 운영 중에 발견

테스트로 다 막을 순 없다. 새는 건 운영 로그로 잡는다. description이 어긋나면 보통 행동에 신호가 나타난다.

- LLM이 같은 도구를 자꾸 다시 부른다
- 호출 실패율이 오른다
- 사용자에게 정정·사과하는 일이 늘어난다

이런 지표가 튀면 description을 의심한다. 막는 게 아니라 빨리 알아채는 안전망.

---
