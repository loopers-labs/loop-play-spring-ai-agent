# Round 2 · 1단계 — Tool 3개 + Mock 데이터 검증

> 엔드포인트: `POST /api/v1/assistant` (Tool Calling 자연어 응답)
> 모델: Ollama `qwen2.5`, temperature 0.3
> Mock seed: 6건 (2024-1234 DELIVERING / 1235 CREATED / 1236 DELIVERED / 1237 COOKING / 1238 CANCELED / 1239 ACCEPTED)

## 검증 — 시나리오 5종

| # | 요청 | 기대 Tool | 기대 결과 | 실제 Tool 호출 | 판정 |
|---|------|-----------|-----------|----------------|------|
| 1 | "주문번호 2024-1234 배달 어디쯤에 있어요?" | getDeliveryStatus | 라이더 "역삼역 사거리" | `[Tool] getDeliveryStatus(orderId=2024-1234)` | ✅ |
| 2 | "주문번호 2024-1234 어떤 메뉴 주문했어요?" | getOrderDetail | 허니콤보/콜라 | `[Tool] getOrderDetail(orderId=2024-1234)` (재시도 2회차) | ✅ (불안정) |
| 3 | "주문번호 2024-1235 방금 시킨 건데 취소해주세요" | cancelOrder | CANCELED | `[Tool] cancelOrder(orderId=2024-1235, reason=고객 요청)` | ✅ |
| 4 | "주문번호 2024-1236 취소해주세요" | cancelOrder | NOT_CANCELABLE (DELIVERED) | `[Tool] cancelOrder(orderId=2024-1236, reason=고객 요청)` | ✅ |
| 5 | "주문번호 2099-9999 배달 어디예요?" | getDeliveryStatus → null | "찾을 수 없다" 안내 | `[Tool] getOrderDetail(orderId=2099-9999)` → null | ✅ (tool 선택은 달랐으나 null 경로 동일) |

### 시나리오 1 — getDeliveryStatus (DELIVERING)
```
현재 라이더는 역삼역 사거리 부근에서 배달 중입니다. 예상 도착 시간은 2026년 6월 1일 오후 6시 52분 48초입니다.
추가로 궁금한 점이 있으신가요?
```
→ `riderLocation`("배달 시작 · 현재 역삼역 사거리 부근")과 `estimatedDeliveryAt` 정확히 반영.

### 시나리오 2 — getOrderDetail (재시도 2회차, 성공)
```
주문하신 메뉴는 허니콤보 1개와 콜라 1.25L 1병이며, 총액은 26,000원입니다.
현재 배달 중으로, 예상 도착 시간은 오늘 오후 6시 52분입니다.
```
→ 메뉴/수량/총액(23,000 + 3,000 = 26,000) 정확.

### 시나리오 3 — cancelOrder → CANCELED
```
주문이 성공적으로 취소되었습니다.
추가로 도움이 필요하시면 알려주세요.
```
→ 2024-1235(CREATED)는 `isCancelable()==true` → `order.cancel()` 실행 후 CANCELED.

### 시나리오 4 — cancelOrder → NOT_CANCELABLE
```
죄송합니다, 현재 주문이 배달 완료된 상태라서 취소할 수 없습니다.
다음에 다시 이용해주시면 감사하겠습니다. ...
```
→ 2024-1236(DELIVERED)는 `isCancelable()==false` → NOT_CANCELABLE outcome을 LLM이 그대로 자연어로 설명.

### 시나리오 5 — 존재하지 않는 주문 (null 처리)
```
해당 주문번호를 찾을 수 없습니다. 다시 한번 주문번호를 확인하신 후 알려주실 수 있을까요?
```
→ tool이 null 반환 → 시스템 프롬프트 [Tool 사용 규칙]의 "null이면 찾을 수 없다고 안내" 동작.

---

## 실패 관찰 — qwen2.5 Tool Calling 불안정성 (평가축 2 자료)

같은 요청을 temperature 0.3으로 여러 번 호출했을 때, **코드가 아니라 모델 레이어에서** 세 가지 변동이 관찰됨. (`[Tool]` 로그 부재 = tool 미호출)

**(A) tool 호출을 평문 텍스트로 누출** — 시나리오 2 1회차:
```
)((((getOrderDetail {"orderId": "2024-1234"}))))

[주문 상세 정보 조회 중입니다.]
```
모델이 tool-call을 실행 트리거가 아니라 응답 본문으로 출력. Spring AI 파서가 인식 못 함 → tool 미실행.

**(B) tool 호출 자체를 생략** — 시나리오 2 1·3회차, 시나리오 5 1·2회차:
```
1) 핵심 답변: 2024-1234번 주문의 상세 정보를 확인해보겠습니다.
...
```
"확인해보겠습니다"라고 말만 하고 tool을 부르지 않음. 실제 데이터 없이 응답 포맷만 채움.

**(C) tool-call 마크업 + 잡토큰 누출** — 시나리오 5 3회차:
```
1) 현재 배달 위치를 확인해 보겠습니다.
2)
ONGL
{"name": "getDeliveryStatus", "arguments": {"orderId": "2099-9999"}}
</tool_call>
```
`</tool_call>` 태그와 `ONGL` 같은 잡토큰이 그대로 노출.

> **함의(README 본문 후보):** 작은 로컬 모델에서 Tool Calling은 결정론적이지 않다. 같은 입력이 (정상 호출 / 텍스트 누출 / 호출 생략)으로 갈린다. 프로덕션이라면 ① tool 호출 실패 시 재시도·폴백, ② 응답에서 tool-call 마크업 누출 감지, ③ 더 큰 모델로의 라우팅이 필요. → Round 3 메모리/멀티턴에서 이 변동성이 누적되면 어떤 영향이 있는지로 연결.

---

## 발견한 버그 — ChatClient.Builder 누적 (수정 완료)

초기 `AssistantController`는 **요청마다** 주입받은 `ChatClient.Builder`로 `.defaultTools(orderTools).build()`를 호출했다. `ChatClient.Builder`는 가변이고 컨트롤러가 단일 인스턴스를 필드로 잡고 있어, 2번째 요청부터 같은 빌더에 Tool이 누적됨:
```
java.lang.IllegalStateException: Multiple tools with the same name
(getOrderDetail, getDeliveryStatus, cancelOrder) found in ToolCallingChatOptions
```
1번째 요청은 통과(3개), 2번째부터 폭발(6개)이 결정적 증거. **수정: `SupportController`처럼 생성자에서 ChatClient를 1회만 build.** 단위 테스트로는 안 잡히고 실제 구동에서만 드러나는 종류의 버그.
