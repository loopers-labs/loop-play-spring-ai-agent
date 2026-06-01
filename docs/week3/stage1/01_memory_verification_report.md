# Chat Memory 검증 — 멀티턴·세션 시나리오 5종

**목적** : Chat Memory가 세션별 대화 맥락을 유지/격리/삭제하는지, 그리고 후속 턴에서 **의도한 tool 동작**이 일어나는지를 검증한다. `/api/v1/assistant`에 5종 시나리오(17 step)를 실행하고, 응답 내용(내용 축)과 `[Tool]` 호출 로그(동작 축)를 2축으로 평가한다.

> 범례: ✅ 통과 · ⚠️ 부분 통과(동작 성공·내용 부정확) · ❌ 실패
> 2축: **동작** = 실제 tool 호출 여부(`log_tool`/`[Tool]` 로그) · **내용** = 응답이 기대에 맞는지
> 각 시나리오에 **Memory 상태**(`GET …/messages`)를 첨부한다.

## 검증 대상 요약

### 구성요소 : `ChatMemoryConfig` · `SessionController` · `AssistantController`

| 구성요소 | 입력 | 출력/결과 |
|---|---|---|
| `MessageChatMemoryAdvisor`(order 10) | conversationId(=X-Session-Id) | 세션 이력을 프롬프트에 주입 |
| `GET /session/{id}/messages` | sessionId | 저장된 `[{type,content}]` |
| `DELETE /session/{id}` | sessionId | 세션 비움(+ `[Session] clear` 로그) |
| `GET /session/ids` | — | 등록된 세션 ID 목록 |

### 입력 데이터 : `OrderMockService`(시드)

| ID | 상태 | 용도 |
|---|---|---|
| 2024-1234 | DELIVERING(역삼역 사거리) | getDeliveryStatus/getOrderDetail 조회 |
| 2024-1235 | CREATED | cancelOrder 취소 성공 경로 |

---

## **요청 형식** : 모든 시나리오 공통, 요청만 교체

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H 'Content-Type: application/json' -H 'X-Session-Id: <세션>' \
  -d '{"message":"<요청>"}'
```

---

## 시나리오 1 — 대명사 재호출 (세션 m1)

**요청**: `"2024-1234 어디쯤?"` → `"그거 언제 도착해요?"`

**응답**
```
1턴: 현재 라이더는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다.
2턴: 현재 라이der는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다.
```

**로그**
```
1턴: [Tool] getDeliveryStatus(orderId=2024-1234)
2턴: (Executing tool call 없음)
```

**Memory 상태** `GET /api/v1/session/m1/messages`
```json
[{"type":"USER","content":"2024-1234 어디쯤?"},
 {"type":"ASSISTANT","content":"현재 라이더는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다."},
 {"type":"USER","content":"그거 언제 도착해요?"},
 {"type":"ASSISTANT","content":"현재 라이der는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다."}]
```

**결과**: ❌ 동작: 2턴에서 getDeliveryStatus **재호출 안 됨**, ✅ 내용: "그거"를 2024-1234로 해결해 정확히 안내

**관찰**: Memory에 직전 답이 남아 모델이 tool을 **다시 부르지 않고 이력을 재사용**했다. 직전 결과가 실데이터라 답은 정확하지만, 기대한 "재호출"은 아니다.

---

## 시나리오 2 — 취소 대상 전환 (세션 m2)

**요청**: `"2024-1234 취소해주세요"` → `"아, 그거 말고 2024-1235 취소해주세요"`

**응답**
```
1턴: 조회 결과, 해당 주문은 이미 배달이 시작되어 취소가 불가능합니다. 다른 도움이 필요하시면 알려주세요.
2턴: 주문이 정상적으로 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있으니 참고해 주세요.
```

**로그**
```
1턴: [Tool] cancelOrder(orderId=2024-1234, reason=고객 요청)   → DELIVERING이라 NOT_CANCELABLE
2턴: [Tool] cancelOrder(orderId=2024-1235, reason=고객 요청)
     [State] 2024-1235 cancel: status CREATED→CANCELED
```

**Memory 상태** `GET /api/v1/session/m2/messages`
```json
[{"type":"USER","content":"2024-1234 취소해주세요"},
 {"type":"ASSISTANT","content":"조회 결과, 해당 주문은 이미 배달이 시작되어 취소가 불가능합니다. 다른 도움이 필요하시면 알려주세요."},
 {"type":"USER","content":"아, 그거 말고 2024-1235 취소해주세요"},
 {"type":"ASSISTANT","content":"주문이 정상적으로 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있으니 참고해 주세요. 다른 도움이 필요하시면 언제든지 알려주세요."}]
```

**결과**: ✅ 동작: 2턴에서 취소 대상이 **1234→1235로 전환**되어 cancelOrder 재호출, ✅ 내용: 1235 취소 성공을 정확히 안내

---

## 시나리오 3 — 이전 턴 orderId 추출 (세션 m3)

**요청**: `"2024-1234 어떤 메뉴 시켰어?"` → `"아까 물어본 그 주문 언제 도착해요?"`

**응답**
```
1턴: 교촌치킨 강남점의 허니콤보 1개와 콜라 1.25L 1병 … 현재 배달 중이며, 약 30분 후 도착 예정입니다.
2턴: 현재 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다.
```

**로그**
```
1턴: [Tool] getOrderDetail(orderId=2024-1234)
2턴: (Executing tool call 없음)
```

**Memory 상태** `GET /api/v1/session/m3/messages`
```json
[{"type":"USER","content":"2024-1234 어떤 메뉴 시켰어?"},
 {"type":"ASSISTANT","content":"주문하신 교촌치킨 강남점의 허니콤보 1개와 콜라 1.25L 1병을 주문하셨습니다. 현재 배달 중이며, 약 30분 후 도착 예정입니다."},
 {"type":"USER","content":"아까 물어본 그 주문 언제 도착해요?"},
 {"type":"ASSISTANT","content":"현재 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다."}]
```

**결과**: ❌ 동작: 2턴에서 getDeliveryStatus **미호출**, ❌ 내용: "역삼역 사거리/15분"은 **환각**

**관찰**: 이 세션은 배달 위치를 조회한 적이 없는데도 2턴이 "역삼역 사거리/약 15분"을 답했다 — 시스템 프롬프트 **예시 2 문구를 복사**한 것이다. 1턴 getOrderDetail의 "약 30분 후"와도 모순. Memory가 맥락은 잇지만 새 tool 호출을 건너뛰고 few-shot을 베껴 **사실과 다른 위치/시간**을 답할 수 있음을 보여준다. 개선: 프롬프트에 "사실 조회는 tool 재호출" 지시 추가, few-shot 예시에서 실주문번호(2024-1234) 제거.

---

## 시나리오 4 — 세션 오염 테스트 (세션 m4a → m4b)

**요청**: m4a `"2024-1234 배달 어디쯤이야?"` → m4b `"그 주문 어디쯤이야?"`

**응답**
```
m4a: 현재 라이더는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다.
m4b: 주문번호가 포함된 메시지를 보내주시면 배달 현황을 확인해 드리겠습니다. 고객님의 주문번호는 무엇인가요?
```

**로그**
```
m4a: [Tool] getDeliveryStatus(orderId=2024-1234)
m4b: (Executing tool call 없음 — 어떤 주문인지 몰라 되물음)
```

**Memory 상태** `GET /api/v1/session/m4a|m4b/messages`
```json
// m4a — 2024-1234 있음
[{"type":"USER","content":"2024-1234 배달 어디쯤이야?"},
 {"type":"ASSISTANT","content":"현재 라이더는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다."}]
// m4b — 2024-1234 없음(오염 안 됨)
[{"type":"USER","content":"그 주문 어디쯤이야?"},
 {"type":"ASSISTANT","content":"주문번호가 포함된 메시지를 보내주시면 배달 현황을 확인해 드리겠습니다. 고객님의 주문번호는 무엇인가요?"}]
```

**결과**: ✅ 동작: 별도 세션 m4b는 m4a 맥락이 없어 tool 미호출·되물음, ✅ 내용: 주문번호를 요청(맥락 없음 올바름)

---

## 시나리오 5 — Memory 삭제 후 맥락 상실 (세션 m5)

**요청**: `"2024-1234 배달 어디쯤이야?"` → `DELETE /session/m5` → `"그거 어디쯤이야?"`

**응답**
```
1턴: 현재 라이더는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다.
삭제 후: 주문번호가 포함되어 있는 메시지를 보내주시면 … 주문번호를 함께 제공해 주실 수 있나요?
```

**로그**
```
1턴: [Tool] getDeliveryStatus(orderId=2024-1234)
삭제: [Session] clear sessionId=m5
삭제 후: (Executing tool call 없음 — 맥락 상실로 되물음)
```

**Memory 상태** `GET /api/v1/session/m5/messages` (삭제 직후)
```json
[]
```

**결과**: ✅ 동작: DELETE 후 messages 빈 배열, 이어진 "그거"는 tool 미호출·되물음, ✅ 내용: 맥락이 사라져 주문번호 요청(올바름)

---

## 종합

**성공률**: 동작 3/5 (60%) · 내용 4/5 (시나리오 3 ❌ 환각)

- 시나리오 1·3에서 **동작 실패**(후속 턴 tool 미호출 — 로그에 `[Tool]` 라인 없음). 1은 직전 실데이터 재사용이라 내용 정확(❌동작/✅내용), 3은 few-shot 복사라 내용까지 틀림(❌/❌).
- 시나리오 2·4·5는 동작·내용 모두 정상. 특히 2는 "그거 말고 1235"로 **취소 대상 전환**이 tool 인자에 반영됨.

### 관찰
- **개선점 ① Memory의 tool 재호출 억제(1·3)**: 후속 턴에서 모델이 이력만으로 답하며 tool을 건너뜀 → 시스템 프롬프트에 "최근 턴이라도 사실(위치·상태) 조회는 tool을 재호출하라" 명시.
- **개선점 ② few-shot 환각(3)**: 예시 2가 실주문번호(2024-1234)·구체 위치를 담아 모델이 복사 → 예시를 가상 데이터로 바꾸거나 "예시는 형식일 뿐 값은 tool 결과로" 강조.
- **검증 교훈**: `response_match`(내용)만으론 시나리오 3을 잡지 못했다(역삼역 매칭 통과). `log_tool`(동작)이 미호출을 잡아 ❌로 분리했다.

### 실행 정보
- **대상/모델**: `/api/v1/assistant`, `qwen2.5`
- **실행 방법**:
  ```bash
  # 1) 시나리오 실행 (서버 자동 기동·종료, 17 step)
  CASES=docs/week3/stage1/memory_verification/cases.json MODEL=qwen2.5 \
    bash docs/verification_memory/run_memory_scenarios.sh

  # 2) 코드 판정 → responses/cases/results.json
  MODEL=qwen2.5 python3 docs/verification_memory/evaluate_memory.py \
    --cases docs/week3/stage1/memory_verification/cases.json
  ```
- **코드 판정**: 15/17 step 통과(step2·8은 `log_tool` 동작 단언으로 의도적 FAIL)
- **세션 목록 확인**: `curl -s http://localhost:8080/api/v1/session/ids | jq` → `["m1","m2","m3","m4a","m4b","m5"]`(삭제 전 기준)
- **실행 브랜치**: `week3/feature01_v2`
- **로그/산출물**: `responses/cases/`(gitignore, `step<N>.json`·`step<N>_logs.log`·`server.log`·`results.json`)
