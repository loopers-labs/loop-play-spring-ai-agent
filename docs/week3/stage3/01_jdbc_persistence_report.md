# Chat Memory 검증 (JDBC 프로필) — 멀티턴·세션 시나리오 5종

**목적** : 저장소를 InMemory → **JDBC(H2)** 로 바꾼 뒤에도 세션별 대화 맥락을 유지/격리/삭제하는지, 후속 턴에서 **의도한 tool 동작**이 일어나는지를 검증한다. 1단계와 **동일한 5종 시나리오(17 step)** 를 `jdbc` 프로필에서 재실행하고, 응답 내용(내용 축)과 `[Tool]` 호출 로그(동작 축)를 2축으로 평가한다.

> 이 실행은 **few-shot 누수 수정 이후**(예시 주문번호 플레이스홀더화 `2099-0001` + "주문번호 없으면 호출 말고 되묻기" 지침 + `cancelOrder` 형식 가드) 기준이다.
> 범례: ✅ 통과 · ⚠️ 부분 통과(동작 성공·내용 부정확) · ❌ 실패
> 2축: **동작** = 실제 tool 호출 여부(`[Tool]` 로그) · **내용** = 응답이 기대에 맞는지
> 각 시나리오에 **Memory 상태**(`GET …/messages`)를 첨부한다 — JDBC(`SPRING_AI_CHAT_MEMORY`)가 데이터 소스.

## 검증 대상 요약

### 구성요소 : `ChatMemoryConfig` · `SessionController` · `AssistantController`

| 구성요소 | 입력 | 출력/결과 |
|---|---|---|
| `JdbcChatMemoryRepository`(자동 구성, `@Profile("!jdbc")`로 InMemory 대체) | conversationId | `SPRING_AI_CHAT_MEMORY` 테이블 CRUD |
| `MessageChatMemoryAdvisor`(order 10) | conversationId(=X-Session-Id) | 세션 이력을 프롬프트에 주입 |
| `GET /session/{id}/messages` | sessionId | 저장된 `[{type,content}]` |
| `DELETE /session/{id}` | sessionId | 세션 비움(+ `[Session] clear` 로그) |

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

> (1-1) 주문하신 음식은 현재 역삼역 사거리 부근에서 배달 중이에요. 도착 예정 시간은 오늘 오후 4시 6분 예정이에요. 조금만 더 기다려 주세요!
>
> (1-2) 주문하신 음식은 현재 역삼역 사거리 부근에서 배달 중이에요. 도착 예정 시간은 오늘 오후 4시 6분으로 변경되었어요. 조금만 더 기다려 주세요!

**로그**

```
(1-1) [Tool] getDeliveryStatus(orderId=2024-1234)
(1-2) [Tool] getDeliveryStatus(orderId=2024-1234)
```

**Memory 상태** (`GET /session/m1/messages`) — USER/ASSISTANT 4건

**결과**: ✅ 동작: "그거"를 직전 주문번호로 보고 `getDeliveryStatus`를 **재호출**, ✅ 내용: 최신 배달 상태로 응답

---

## 시나리오 2 — 취소 대상 전환 (세션 m2)

**요청**: `"2024-1234 취소해주세요"` → `"아, 그거 말고 2024-1235 취소해주세요"`

**응답**

> (2-1) 죄송합니다, 현재 주문은 배달 중이라 취소가 불가능합니다. 상담사 연결을 도와드릴까요?
>
> (2-2) 죄송합니다, 주문 번호 2024-1235가 정상적으로 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있으니 참고해주세요. …

**로그**

```
(2-1) [Tool] cancelOrder(orderId=2024-1234, reason=단순 변심)
(2-2) [Tool] cancelOrder(orderId=2024-1235, reason=단순 변심)
      [State] 2024-1235 cancel: status CREATED→CANCELED
```

**Memory 상태** (`GET /session/m2/messages`) — USER/ASSISTANT 4건, `2024-1235` 포함

**결과**: ✅ 동작: 취소 대상이 1234→1235로 전환되어 `cancelOrder(2024-1235)` 호출, ✅ 내용: 1234는 배달 중이라 취소 불가 → 1235 취소 성공 안내 정확

---

## 시나리오 3 — 이전 턴 orderId 추출 (세션 m3)

**요청**: `"2024-1234 어떤 메뉴 시켰어?"` → `"아까 물어본 그 주문 언제 도착해요?"`

**응답**

> (3-1) 주문하신 메뉴는 교촌치킨 강남점의 허니콤보와 콜라 1.25L입니다. 각각 한 개씩 주문하셨어요. 총 금액은 26,000원이에요.
>
> (3-2) 현재 주문은 배달 중이며, 라이더는 역삼역 사거리 부근에 있습니다. 도착 예정 시간은 오늘 오후 4시 6분경으로 예상됩니다. …

**로그**

```
(3-1) [Tool] getOrderDetail(orderId=2024-1234)
(3-2) [Tool] getDeliveryStatus(orderId=2024-1234)
```

**Memory 상태** (`GET /session/m3/messages`) — USER/ASSISTANT 4건

**결과**: ✅ 동작: 3-2에서 메모리의 이전 턴 orderId(2024-1234)를 추출해 `getDeliveryStatus` 재호출, ✅ 내용: 메뉴 조회 → 배달 조회로 맥락 연결 정확

---

## 시나리오 4 — 세션 오염 테스트 (세션 m4a → m4b 분리)

**요청**: (m4a) `"2024-1234 배달 어디쯤이야?"` / (m4b) `"그 주문 어디쯤이야?"`

**응답**

> (m4a) 라이더님이 역삼역 사거리 부근에서 배달 중이세요. 도착 예정 시간은 오늘 오후 4시 6분 정도예요.
>
> (m4b) 죄송합니다, 해당 주문을 찾지 못했습니다. 주문 번호를 다시 한번 확인해주세요.

**로그**

```
(m4a) [Tool] getDeliveryStatus(orderId=2024-1234)
(m4b) [Tool] getDeliveryStatus(orderId=2099-0001)   ← 예시 플레이스홀더를 끌어옴 → not-found
```

**Memory 상태**

```
GET /session/m4a/messages → 2024-1234 포함 (USER/ASSISTANT 2건)
GET /session/m4b/messages → 2024-1234 없음 (오염 안 됨)
```

**결과**: ⚠️ 동작: m4a는 정상 호출, m4b는 tool 미호출 기대였으나 예시 번호(2099-0001)로 호출, ✅ 내용: 2099-0001이 실재하지 않아 not-found → "주문번호를 다시 확인" 안내(올바름)

**관찰**: 세션은 제대로 분리됐다 — m4b 메모리엔 1234가 없다(m4a와 안 섞임). m4b가 tool을 부른 건 모델이 **시스템 프롬프트에 적힌 예시 주문번호를 그대로 따라 썼기** 때문이다. 다만 이제 그 예시가 mock에 없는 `2099-0001`이라, 조회해도 "없는 주문"이 되어 **"주문번호를 다시 확인해 달라"는 맞는 답**으로 이어졌다. 예전처럼 예시가 실제 주문(`2024-1234`)이었다면 엉뚱한 주문 정보가 그대로 안내됐을 것이다. 즉 **예시를 따라 쓰는 버릇은 남았지만, 더는 잘못된 답으로 이어지지 않는다.**

---

## 시나리오 5 — Memory 삭제 후 맥락 상실 (세션 m5)

**요청**: `"2024-1234 배달 어디쯤이야?"` → `DELETE /session/m5` → `"그거 어디쯤이야?"`

**응답**

> (5-1) 주문 번호 2024-1234의 배달이 현재 역삼역 사거리 부근에서 진행 중이며, 도착 예정 시간은 오늘 오후 4시 6분경으로 예상됩니다. …
>
> (5-3, 삭제 후) 먼저 주문 번호를 알려주시겠어요? 직전 대화에서 나온 주문 중 어떤 것이 궁금하신지 말씀해 주세요.

**로그**

```
(5-1) [Tool] getDeliveryStatus(orderId=2024-1234)
(5-2) DELETE /session/m5 → HTTP 200 ([Session] clear sessionId=m5)
(5-3) [Tool] 없음   ← 미호출(기대대로)
```

**Memory 상태**

```
GET /session/m5/messages (삭제 직후) → []   ← 빈 배열, JDBC clear 정상
```

**결과**: ✅ 동작: 5-1 호출 / 5-2 삭제 200 / 5-3-M **빈 배열** / 5-3 tool 미호출, ✅ 내용: 맥락이 사라져 주문번호를 되물음(올바름)

**관찰**: **few-shot 누수 수정 효과 확인** — 직전 실행(수정 전)에서는 메모리가 비었는데도 예시의 `2024-1234`로 `getDeliveryStatus`를 호출했으나, 수정 후에는 tool을 호출하지 않고 주문번호를 되묻는다. 삭제 직후 `[]`로 JDBC clear도 정상.

---

## 종합

> 범례: ✅ 통과 · ⚠️ 부분 통과 · ❌ 실패

**성공률**: step 기준 **16/17 통과** · 동작 축(시나리오) **4/5**(S4 m4b만 ❌) · 내용 축 **5/5**

- **저장소(JDBC) 동작은 전부 정상**: 적재(모든 세션 messages 일치) · 조회 · 세션 격리(S4) · 삭제(S5 빈 배열) — JDBC가 InMemory와 동일하게 동작.
- **S5(삭제 후 맥락 상실)가 ❌→✅로 전환**: few-shot 누수 수정(예시 플레이스홀더 + "주문번호 없으면 되묻기" 지침)이 직접 작용.
- 남은 ❌(S4 m4b)는 모델이 예시 주문번호를 복사한 것이지만, 수정으로 그 번호가 not-found라 **응답은 올바르게 자기교정**됨(내용 ✅). 저장소와 무관한 LLM 비결정 동작.

### JDBC
- **저장소 보존량 ≠ 컨텍스트 주입량**: 테이블엔 행이 계속 쌓이지만 `MessageWindowChatMemory(maxMessages=20)`가 LLM에 주입하는 건 최근 20개뿐이다.

### 관찰
- **개선점 ① 예시 주문번호 echo 잔존(S4 m4b)**: 모델이 맥락 없을 때 예시 번호를 복사하는 경향은 남음 → 현재는 플레이스홀더가 not-found라 무해. 더 줄이려면 Tool 설명에서 구체 예시를 형식 토큰(`YYYY-XXXX`)만 남기고 제거하는 방안.
- **개선점 ② 후속 턴 tool 재호출(S1)**: 이번 실행에선 1-2가 정상 재호출(✅). 모델 비결정성에 따라 tool 호출 대신 "회상"으로 흔들릴 수 있어 프롬프트의 "변하는 사실은 매 턴 재호출" 지침 필요. 
- **InMemory 대비**: 저장소 교체로 인한 회귀 없음. 동작 축의 회차별 차이(S1·S4·S5)는 14b 비결정성에 기인.

### 실행 정보
- **대상/모델**: `/api/v1/assistant` · `qwen2.5:14b`(temp 0.3) — 1단계는 `qwen2.5`였음
- **프로필**: `jdbc` 단독 (`SPRING_PROFILES_ACTIVE=jdbc`, server.log: `The following 1 profile is active: "jdbc"`, `H2 console available`)
- **실행 방법**:
  ```bash
  SPRING_PROFILES_ACTIVE=jdbc MODEL=qwen2.5:14b \
  CASES=docs/week3/stage3/memory_verification/cases.json \
  bash docs/verification_memory/run_memory_scenarios.sh
  MODEL=qwen2.5:14b python3 docs/verification_memory/evaluate_memory.py \
    --cases docs/week3/stage3/memory_verification/cases.json
  ```
- **실행 브랜치**: `week3/feature01_v5(step3)`
- **산출물**: `docs/week3/stage3/memory_verification/responses/cases/`(step별 JSON·로그, `results.json`, `server.log`)

---

## 부록 

### H2 콘솔 직접 확인

인메모리 H2라 외부 프로세스에서 조회 불가 — 실행 중인 앱의 콘솔로만 접근한다.

1. `http://localhost:8080/h2-console` — JDBC URL `jdbc:h2:mem:baedal`, User `sa`, Password 빈칸
2. 아래 쿼리 실행.

```sql
SELECT * FROM SPRING_AI_CHAT_MEMORY ORDER BY "timestamp";
```

**결과** (jdbc 프로필 기동 후 데모 세션 2개로 적재 — `h2-demo-a` 2턴, `h2-demo-b` 1턴):

| conversation_id | type | content | timestamp |
|---|---|---|---|
| h2-demo-a | USER | 2024-1234 어디쯤이야? | 2026-06-02 16:32:21.315 |
| h2-demo-a | ASSISTANT | 현재 주문은 역삼역 사거리 부근에서 배달 중이에요. 도착 예정 시간은 오늘 오후 4시 46분 정도로 예상됩니다. | 2026-06-02 16:32:21.316 |
| h2-demo-a | USER | 그거 언제 도착해? | 2026-06-02 16:32:21.317 |
| h2-demo-a | ASSISTANT | 앞서 말씀드린 대로 현재 주문은 오늘 오후 4시 46분경 도착할 예정이에요. 상태가 변경되면 다시 확인해주세요. | 2026-06-02 16:32:21.318 |
| h2-demo-b | USER | 2024-1235 취소해줘 | 2026-06-02 16:32:41.984 |
| h2-demo-b | ASSISTANT | 주문 번호 2024-1235의 주문이 취소되었습니다. 결제 취소는 카드사 정책에 따라 최대 7영업일까지 소요될 수 있으니 참고해주세요. 추가로 도움이 필요하시면 말씀해주시기 바랍니다. | 2026-06-02 16:32:41.985 |

- `conversation_id`로 세션이 분리 저장되고, `timestamp` 순으로 USER/ASSISTANT가 번갈아 쌓임이 확인된다(JDBC 영속 동작).


### JDBC 전환 설정 요약

| 파일 | 변경 |
|------|------|
| `build.gradle` | `spring-ai-starter-model-chat-memory-repository-jdbc` + `com.h2database:h2` |
| `memory/ChatMemoryConfig.java` | `chatMemoryRepository()`에 `@Profile("!jdbc")` |
| `application-jdbc.yml` | `spring.ai.chat.memory.repository.jdbc.platform: postgresql` |
| `logback-spring.xml` | dev/prod 외 프로필용 fallback root(CONSOLE) |

- **`platform: postgresql`**: Spring AI 1.0.0 스타터 jar에 `schema-h2.sql`이 없어(postgresql·hsqldb·sqlserver·mariadb만 존재) `initialize-schema`가 `No schema scripts found`로 실패한다. H2를 `MODE=PostgreSQL`로 띄우므로 `@@platform@@`을 postgresql로 고정해 `schema-postgresql.sql`을 로드한다.
- **logback fallback**: 기존 설정은 root appender를 `dev`/`prod` 블록에서만 정의해 `jdbc` 단독 기동 시 콘솔이 무출력이 된다. fallback root로 기동/SQL/`[Tool]` 로그(INFO)가 보인다.