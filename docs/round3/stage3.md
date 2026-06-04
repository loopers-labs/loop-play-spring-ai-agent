# 3단계: InMemory vs JdbcChatMemory — 의사결정 트리


## 검증 — 시나리오 5종 (JDBC 프로필)

> 1단계와 동일한 시나리오를 JDBC 프로필에서 다시 실행한다. 응답이 InMemory와 동일해야 한다.

### 시나리오 1 (JDBC)

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s1" -d '{"message":"2024-1234 어디쯤이에요?"}'
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s1" -d '{"message":"그거 몇 시에 도착해요?"}'
```

**1회차 응답**

```
현재 라이더는 역삼역 사거리 부근에서 배송 중입니다. 예상 도착 시간은 2026년 5월 31일 오후 5시 5분경입니다.
```

**2회차 응답**

```
예상 도착 시간은 2026년 5월 31일 오후 5시 5분경입니다. 정확한 도착 시간은 실시간 교통 상황에 따라 약간 변동될 수 있습니다.
```

**Memory 상태**

```json
[
  { "type": "USER",      "content": "2024-1234 어디쯤이에요?" },
  { "type": "ASSISTANT", "content": "현재 라이더는 역삼역 사거리 부근에서 배송 중입니다. 예상 도착 시간은 2026년 5월 31일 오후 5시 5분경입니다." },
  { "type": "USER",      "content": "그거 몇 시에 도착해요?" },
  { "type": "ASSISTANT", "content": "예상 도착 시간은 2026년 5월 31일 오후 5시 5분경입니다. 정확한 도착 시간은 실시간 교통 상황에 따라 약간 변동될 수 있습니다." }
]
```

> 1단계 InMemory 실행과 응답이 동일하다. 저장소가 바뀌어도 LLM이 받는 프롬프트 구조는 같기 때문이다. `MessageChatMemoryAdvisor`가 저장소 종류에 상관없이 대화 이력을 꺼내 프롬프트 앞에 붙여주므로, LLM 입장에서는 Memory가 HashMap에 있든 H2에 있든 차이가 없다.
>
> "그거 몇 시에 도착해요?"에 Tool 재호출 없이 1회차 응답에서 도착 시간을 꺼낸 것도 1단계와 같다. ASSISTANT_PROMPT의 재사용 지시가 저장소 종류와 무관하게 동작한다는 확인이기도 하다.
>
> 단, 여기서 돌려준 "오후 5시 5분"은 1회차 조회 시점 기준이다. 재시작 실험 2에서 파일 H2를 쓰면 이 오래된 시간이 재시작 이후에도 그대로 답변에 나온다. 저장소 교체로 생기는 새로운 문제다.

---

### 시나리오 2 (JDBC) — 취소 대상 전환

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s2" -d '{"message":"2024-1234 취소해주세요"}'
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s2" -d '{"message":"아 그거 말고 2024-1235 취소해주세요"}'
```

**1회차 응답**

```
죄송합니다, 이 주문은 이미 조리가 시작되어 취소할 수 없습니다. 현재 상태는 배달 중입니다. 다른 도움이 필요하시면 알려주세요.
```

**2회차 응답**

```
주문 2024-1235가 성공적으로 취소되었습니다. 다른 도움이 필요하시면 언제든지 말씀해주세요!
```

**Memory 상태**

```json
[
  { "type": "USER",      "content": "2024-1234 취소해주세요" },
  { "type": "ASSISTANT", "content": "죄송합니다, 이 주문은 이미 조리가 시작되어 취소할 수 없습니다. 현재 상태는 배달 중입니다. 다른 도움이 필요하시면 알려주세요." },
  { "type": "USER",      "content": "아 그거 말고 2024-1235 취소해주세요" },
  { "type": "ASSISTANT", "content": "주문 2024-1235가 성공적으로 취소되었습니다. 다른 도움이 필요하시면 언제든지 말씀해주세요!" }
]
```

**콘솔 로그**

```
[Assistant] sessionId=jdbc-s2, message=2024-1234 취소해주세요
[Tool] cancelOrder(orderId=2024-1234, reason=고객 요청)
LLM 호출 완료 — 118180ms | 입력 토큰: 1226 | 출력 토큰: 82 | 총 토큰: 1308

[Assistant] sessionId=jdbc-s2, message=아 그거 말고 2024-1235 취소해주세요
[Tool] cancelOrder(orderId=2024-1235, reason=고객 요청)
LLM 호출 완료 — 123067ms | 입력 토큰: 1409 | 출력 토큰: 61 | 총 토큰: 1470
```

> 1단계 InMemory 결과(1회차 1222토큰, 2회차 1409토큰)와 4토큰 이내로 일치한다. 저장소가 H2로 바뀌어도 LLM이 받는 프롬프트 구조는 동일하다는 확인이다.
> "그거 말고"로 취소 대상이 1234→1235로 전환됐다. InMemory와 동일한 동작.

---

### 시나리오 3 (JDBC) — 이전 턴 참조

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s3" -d '{"message":"2024-1234 주문 상태 확인해줘"}'
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s3" -d '{"message":"아까 물어본 그 주문 언제 도착해요?"}'
```

**1회차 응답**

```
2024-1234번 주문의 상태는 배달 중입니다. 현재는 교촌치킨 강남점에서 준비 중이며, 예상 배달 시간은 2026년 5월 31일 오후 9시 29분입니다.
```

**2회차 응답**

```
2024-1234번 주문의 예상 배달 시간은 2026년 5월 31일 오후 9시 29분입니다. 안전한 배달을 위해 조금 더 기다려주시면 감사하겠습니다.
```

**Memory 상태**

```json
[
  { "type": "USER",      "content": "2024-1234 주문 상태 확인해줘" },
  { "type": "ASSISTANT", "content": "2024-1234번 주문의 상태는 배달 중입니다. 현재는 교촌치킨 강남점에서 준비 중이며, 예상 배달 시간은 2026년 5월 31일 오후 9시 29분입니다." },
  { "type": "USER",      "content": "아까 물어본 그 주문 언제 도착해요?" },
  { "type": "ASSISTANT", "content": "2024-1234번 주문의 예상 배달 시간은 2026년 5월 31일 오후 9시 29분입니다. 안전한 배달을 위해 조금 더 기다려주시면 감사하겠습니다." }
]
```

**콘솔 로그**

```
[Assistant] sessionId=jdbc-s3, message=2024-1234 주문 상태 확인해줘
[Tool] getOrderDetail(orderId=2024-1234)
LLM 호출 완료 — 144550ms | 입력 토큰: 1362 | 출력 토큰: 132 | 총 토큰: 1494

[Assistant] sessionId=jdbc-s3, message=아까 물어본 그 주문 언제 도착해요?
LLM 호출 완료 — 121736ms | 입력 토큰: 725 | 출력 토큰: 76 | 총 토큰: 801
```

> "아까 물어본 그 주문"에서 Memory의 2024-1234를 정확히 꺼냈다. Tool 재호출 없이 1회차 ASSISTANT 응답에서 도착 시간을 재사용했다. InMemory(1348→742) 대비 JDBC(1362→725)는 17토큰 이내로 동일하다.

---

### 시나리오 4 (JDBC) — 세션 격리

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-A4" -d '{"message":"2024-1234 배달 현황 알려줘"}'
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-B4" -d '{"message":"그 주문 어디쯤이야?"}'
```

**세션 A 응답**

```
현재 배달 상태는 "배달 중"이며, 라이더의 위치는 역삼역 사거리 부근입니다. 예상 도착 시간은 2026년 5월 31일 오후 5시 13분 11초입니다.
```

**세션 B 응답**

```
어떤 주문을 말씀하시는 건가요? 최근에 처리한 주문번호를 알려주시면 도와드리겠습니다.
```

**세션 B Memory**

```json
[
  { "type": "USER",      "content": "그 주문 어디쯤이야?" },
  { "type": "ASSISTANT", "content": "어떤 주문을 말씀하시는 건가요? 최근에 처리한 주문번호를 알려주시면 도와드리겠습니다." }
]
```

> B는 A의 1234 맥락을 전혀 모른다. InMemory와 JDBC에서 격리가 구현되는 방식이 다르다.
>
> InMemory에서는 세션마다 별개의 `List<Message>` 객체가 HashMap에 들어간다. JDBC에서는 모든 세션 데이터가 `SPRING_AI_CHAT_MEMORY` 테이블 한 곳에 섞여 있고, `conversation_id` 컬럼으로 구분한다. 격리는 `WHERE conversation_id = ?` 쿼리 하나가 담당한다.
>
> DB 관리자가 테이블 전체를 조회하면 모든 고객 대화를 볼 수 있다. JDBC로 전환하는 순간 DB 접근 권한을 가진 사람이 데이터를 볼 수 있게 된다. 컬럼 암호화나 접근 제어가 필요해지는 시점이다.

---

### 시나리오 5 (JDBC) — DELETE 후 맥락 소실

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s5" -d '{"message":"2024-1234 주문 상태 알려줘"}'
# 동일 메시지 재시도
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s5" -d '{"message":"2024-1234 주문 상태 알려줘"}'
curl -s -X DELETE http://localhost:8080/api/v1/session/jdbc-s5
curl -s http://localhost:8080/api/v1/session/jdbc-s5/messages
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: jdbc-s5" -d '{"message":"그거 언제 도착해요?"}'
```

**1회차 응답 (1차 시도) — Tool 호출 의사코드 누출**

```
orderid = "2024-1234"
orderId = orderid
result = getOrderDetail(orderId=orderid)
print(result)
```

**1회차 응답 (2차 시도) — 의사코드 + 중국어 혼합**

```
orderid = "2024-1234"
orderId = orderid
结果显示：{'status': '准备中', 'message': None}
您的订单编号为 2024-1234 的状态是「准备中」。
```

**DELETE 직후 Memory 상태**

```json
[]
```

**삭제 후 응답**

```
어떤 주문을 말씀하시는 건가요? 최근에 주문하신 그 주문의 배달 예정 시간을 알려드리겠습니다.
```

> **1차·2차 시도 모두 정상 응답 불가** — qwen2.5가 Tool을 실제로 호출하지 않고 파이썬 의사코드로 "어떻게 호출할지"를 출력했다. 모델 내부 추론 과정이 응답에 누출된 것이다. ASSISTANT_PROMPT가 길어지거나 프롬프트 조합이 특정 조건에 걸릴 때 qwen2.5에서 간헐적으로 나타나는 비결정적 장애 모드다. 2단계 회고 5("금지 조항이 실험 자체를 망가뜨렸다")에서 관찰한 패턴과 동일하다.
>
> **2차 시도의 할루시네이션** — Tool 응답 없이 `{'status': '准备中'}` ('准备中' = 준비 중)으로 상태를 만들어냈다. 실제 2024-1234는 DELIVERING 상태인데 오답을 냈고, 응답 언어까지 중국어로 전환됐다. Memory에 이 잘못된 응답 쌍이 쌓인 채 대화가 이어지면 오정보가 맥락으로 주입된다.
>
> **DELETE 후 맥락 소실 확인** — DELETE 후 `[]`. 잘못된 두 쌍이 Memory에 쌓여 있었지만 DELETE가 모두 지웠다. "그거 언제 도착해요?"에 "어떤 주문을 말씀하시는 건가요?"로 되물었다. 맥락 소실 동작은 InMemory와 동일.
>
> 삭제 후 응답 "어떤 주문을 말씀하시는 건가요? 최근에 주문하신 **그 주문**의 배달 예정 시간을 알려드리겠습니다"는 의문과 참조가 충돌한다. 모델이 "어떤 주문?"이라고 물으면서 동시에 "그 주문"이라고 지칭했다. Memory가 없을 때 지시 대명사를 처리하는 방식의 불안정성이다.

---

## H2 Console 캡처


**쿼리**

```sql
SELECT * FROM SPRING_AI_CHAT_MEMORY ORDER BY "timestamp";
```

**H2 Console 화면 캡처**

![H2 Console — SPRING_AI_CHAT_MEMORY 테이블](docs/round3/images/h2-console-memory.png)


> USER→ASSISTANT→USER→ASSISTANT 행들의 timestamp가 밀리초 간격으로 찍혀 있다. 대화가 진행되는 동안 실시간으로 쓰는 게 아니라, 응답이 완성된 뒤 한 번에 저장하기 때문이다.
>
> TOOL 타입 행이 없다. Tool을 호출한 세션도 마찬가지다. `cancelOrder(2024-1235)`나 `getOrderDetail(2024-1234)` 같은 Tool 호출의 흔적은 DB에 없다. Tool 결과는 ASSISTANT 응답 문장에 녹아들어 있을 뿐이다. "주문 2024-1235가 성공적으로 취소되었습니다"라는 ASSISTANT 행이 Tool 호출의 유일한 증거다.
>
> ASSISTANT 행의 content 길이가 짧다. SUBSTRING(content, 1, 80)으로 잘린 결과다. 실제로 DB에 들어간 content는 전체 응답 문장이고, 이 안에 주문번호·금액·메뉴 정보까지 포함된다. 고객 주문 정보가 평문으로 저장된다는 뜻이다.

---

## 재시작 실험 — 영속성 검증

### 실험 1: `jdbc:h2:mem:...` (인메모리 H2)

```bash
# 대화 기록
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: restart-test" \
  -d '{"message":"2024-1234 주문 상태 알려줘"}'

# 앱 종료 후 재시작
./gradlew bootRun --args='--spring.profiles.active=jdbc'

# 재시작 후 "그거" 질문
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: restart-test" \
  -d '{"message":"그거 언제 도착해요?"}'
```

**재시작 전 Memory 상태 (`/api/v1/session/restart-test/messages`)**

```json
[
  { "type": "USER",      "content": "2024-1234 주문 상태 알려줘" },
  { "type": "ASSISTANT", "content": "2024-1234번 주문의 상태는 배달 중입니다. 예상 도착 시간과 메뉴 정보를 안내했습니다." }
]
```

**재시작 후 세션 목록 및 Memory 상태**

```bash
curl -s http://localhost:8080/api/v1/session/ids       # []
curl -s http://localhost:8080/api/v1/session/restart-test/messages  # []
```

```
세션 목록: []
restart-test Memory: []
```

**재시작 후 "그거 언제 도착해요?" 응답**

```
어떤 주문을 말씀하시는 건가요? 최근에 언급된 주문번호를 알려주시면 감사하겠습니다.
```

> 세션 목록이 `[]`가 됐다. `DB_CLOSE_DELAY=-1` 옵션이 있는데도. 이 옵션은 "마지막 커넥션이 닫혀도 DB를 유지하라"는 뜻이지, JVM 재시작을 버텨낸다는 게 아니다. JVM이 꺼지면 힙도 사라지고 H2도 함께 사라진다.
>
> InMemory(Spring의 `ConcurrentHashMap`)와 `jdbc:h2:mem`은 결국 같은 운명이다. 저장 위치만 다를 뿐 둘 다 JVM 메모리 위에 산다. JDBC 드라이버를 거친다고 영속성이 생기는 게 아니다.
>
> 그렇다면 `jdbc:h2:mem`이 InMemory보다 나은 점은 없을까. SQL 쿼리로 데이터를 직접 조회할 수 있고, 트랜잭션 지원이 있고, DB 스키마 검증이 된다. 테스트 환경에서 실제 DB 동작을 흉내 낼 때 유용하다. 하지만 운영에서 영속성 목적으로 쓸 이유는 없다.

### 실험 2: `jdbc:h2:file:./data/baedal` (파일 기반 H2)

`application-jdbc.yml`의 `url`을 아래로 변경하고 `initialize-schema: always`로 함께 설정:

```yaml
url: jdbc:h2:file:./data/baedal;MODE=PostgreSQL
...
initialize-schema: always
```

**재시작 전 대화**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: file-test" -d '{"message":"2024-1234 주문 상태 알려줘"}'
```

```
2024-1234번 주문의 상태는 배달 중입니다. 예상 도착 시간은 2026년 5월 31일 오후 5시 15분입니다.
허니콤보(1개, 23,000원)와 콜라 1.25L(1개, 3,000원). 총 26,000원.
```

파일 생성 확인:
```
./data/baedal.mv.db  (24576 bytes)
```

**재시작 후 Memory 상태**

```bash
curl -s http://localhost:8080/api/v1/session/ids
# ["file-test"]

curl -s http://localhost:8080/api/v1/session/file-test/messages
```

```json
[
  { "type": "USER",      "content": "2024-1234 주문 상태 알려줘" },
  { "type": "ASSISTANT", "content": "2024-1234번 주문의 상태는 배달 중입니다. 예상 도착 시간은 2026년 5월 31일 오후 5시 15분입니다..." }
]
```

**재시작 후 "그거" 질문**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: file-test" -d '{"message":"그거 언제 도착해요?"}'
```

```
2024-1234번 주문의 예상 도착 시간은 2026년 5월 31일 오후 5시 15분입니다. 안전하게 배달이 이루어질 수 있도록 기다려주시기 바랍니다.
```

> 재시작 후 세션 목록에 `file-test`가 살아있고, "그거"가 1234를 가리켰다. 새 JVM이 `baedal.mv.db`를 열면서 이전 대화 이력을 그대로 가져온 것이다.
>
> 그런데 답변에 "오후 5시 15분"이 그대로 나왔다. 재시작 전에 조회한 시각이다. 재시작 사이에 실제 배달 상황이 바뀌었을 수 있는데, Memory에 저장된 오래된 정보를 꺼내 답한다. 1단계 시나리오 1에서 지적한 문제가 여기서 더 심각해진다. InMemory는 재시작하면 Memory가 사라지니까 이 문제가 자동으로 해소됐다. 파일 영속 Memory를 쓰면 오래된 정보가 계속 살아남아 잘못된 답변을 준다.
>
> 실시간 데이터(배달 위치, 도착 시간)를 Memory에서 재사용하지 않도록 프롬프트를 수정하거나, 배달 완료 시점에 해당 세션 Memory를 지워야 한다.

### 영속성 비교 표

| 저장소 설정 | 재시작 후 Memory 유지? | 비고 |
|---|---|---|
| InMemory (기본) | 아니오 | JVM 힙에만 존재 |
| `jdbc:h2:mem:...` | 아니오 | JVM 생존 동안만 유지, 재시작 시 소실 |
| `jdbc:h2:file:...` | 예 | `baedal.mv.db` 파일이 남아 재시작 후에도 유지 |

> InMemory와 `jdbc:h2:mem`의 재시작 결과가 같다. "JDBC를 쓰면 영속성이 생긴다"는 오해를 가장 직접적으로 깨주는 비교다. 영속성은 저장 방식이 아니라 저장 위치에서 온다.
>
> `jdbc:h2:file`이 처음으로 재시작을 버텼지만, 단일 서버에서만 의미가 있다. 서버가 2대 이상이면 파일이 각각 따로 존재하므로 세션이 어느 서버로 라우팅되느냐에 따라 Memory가 달라진다. 멀티 인스턴스 환경이라면 파일이 아니라 공유 DB가 필요하다.

---

## 설계 결정 문서 — 의사결정 트리

### 저장소 선택 기준표

| 운영 조건 | Yes/No | 선택 |
|---|---|---|
| 서비스가 로드밸런서 뒤 멀티 인스턴스로 뜨는가? | Yes | JDBC (공유 DB 필요) |
| 서버 재시작 후에도 고객 대화가 이어져야 하는가? | Yes | JDBC (파일 또는 외부 DB) |
| 법적/감사 이유로 상담 이력을 N년 보관해야 하는가? | Yes | JDBC + 외부 DB (백업 필요) |
| 단일 인스턴스 + 세션이 분 단위로 짧은가? | No | JDBC 필요 |

---

### InMemory로 충분한 3가지 조건

세 조건이 모두 맞아야 InMemory가 안전한 선택이다. 하나라도 빠지면 재시작이나 스케일아웃 시점에 데이터가 날아가거나 세션이 엉킨다.

1. **단일 인스턴스**: 로드밸런서 없이 서버 1대로만 운영한다면, 세션이 항상 같은 JVM으로 들어온다. 인스턴스 간 Memory 공유 문제가 생길 여지가 없다.
2. **짧은 세션 수명**: 배달 상담은 주문 하나가 마무리되면 끝나는 경우가 많다. 세션 길이가 앱 재시작 간격보다 훨씬 짧다면, 재시작으로 Memory가 초기화돼도 고객 입장에서는 차이가 없다.
3. **개발/스테이징 환경**: 재시작이 잦고 이력이 남을 필요가 없는 환경이라면 InMemory가 충분하다. 운영 DB를 붙이는 번거로움 없이 빠르게 확인할 수 있다.

---

### JDBC가 필요한 3가지 조건

1. **멀티 인스턴스 배포**: 로드밸런서가 요청을 여러 인스턴스로 분산하면, 인스턴스 A에 쌓인 Memory가 인스턴스 B에는 없다. 고객이 같은 세션 ID로 요청을 보내도 어느 서버로 라우팅되느냐에 따라 맥락이 달라진다. 공유 DB 없이는 해결할 방법이 없다.
2. **서버 재시작 후 대화 연속성**: 배포나 크래시 이후 재기동 시 Memory가 비어 있으면 "아까 그 주문"처럼 이전 대화를 참조하는 질문에 답할 수 없다. 상담이 진행 중인 상태에서 배포가 일어나는 경우라면 영향이 더 크다.
3. **감사 로그 / 컴플라이언스**: 상담 이력을 법적으로 보관해야 하거나, 품질 관리 목적으로 나중에 특정 대화를 검색·조회해야 할 때는 Memory가 DB에 영속되어야 한다. 로그 파일로 대체할 수는 있지만, 대화 단위로 쿼리하려면 결국 DB가 필요하다.

---

### 배달 실제 운영 시 DB 선택

PostgreSQL을 선택했다. 결정적인 이유는 Round 4다.

Round 4에서 PgVector를 사용할 예정이라, 지금 PostgreSQL을 골라두면 Chat Memory와 벡터 저장소가 같은 인스턴스에 들어간다. 별도 DB를 추가로 관리하지 않아도 된다.

| 옵션 | 평가 |
|---|---|
| PostgreSQL | Round 4에서 PgVector 사용 예정 → Chat Memory + Vector Search를 같은 인스턴스에서 운영 가능. 인덱스, 파티셔닝, 트랜잭션 성숙도 높음 |
| MySQL | PostgreSQL과 운영 방식이 유사하지만 PgVector 미지원 → Round 4에서 별도 DB를 추가해야 함 |
| Redis | 속도는 빠르지만 상담 이력 장기 보관에 맞지 않는다. AOF/RDB를 켜도 메모리 DB 특성상 대용량 이력 보관에 부적합 |
| DynamoDB | 관리형이라 운영 부담이 낮지만, Spring AI JdbcChatMemoryRepository가 JDBC 기반이라 커스텀 어댑터를 별도로 만들어야 함 |

Redis는 빠르다는 장점이 있어서 검토했지만 배제했다. TTL 제어와 장기 보관이 관계형 DB보다 까다롭고, 대화 이력을 `conversation_id` 기준으로 쿼리하는 작업이 Redis 구조에 자연스럽지 않다.

---

### JDBC 도입 시 동시에 고려할 비기능 요구사항

JDBC로 전환하면 데이터가 쌓이기 시작한다. 그 순간부터 세 가지 문제가 동반 과제가 된다. 배포 이후에 따로 붙이기 어려운 것들이라 설계 단계에서 같이 잡아두는 게 낫다.

| 요구사항 | 내용 |
|---|---|
| **TTL (데이터 만료)** | 배달 상담은 대부분 당일 완료되고 재문의도 90일 안에 끝난다. 그 이후 행을 계속 쌓아두면 테이블이 무한 증가한다. 배치 잡이나 DB 파티션 TTL로 오래된 행을 자동 삭제해야 한다 |
| **인덱스** | `conversation_id` 컬럼에 인덱스가 없으면 세션 조회가 전체 테이블 스캔이 된다. `timestamp` 복합 인덱스로 최신 N개 조회 성능도 확보해야 한다. 트래픽이 높으면 `conversation_id` 해시 기반 파티셔닝도 고려할 만하다 |
| **암호화** | `content` 컬럼에는 고객이 대화 중 언급한 배달지 주소, 전화번호, 주문번호가 그대로 들어간다. H2 Console 화면에서 본 것처럼 평문이다. 컬럼 레벨 암호화나 애플리케이션 레벨 암호화가 없으면 DB 접근 권한만 있어도 고객 정보가 노출된다 |

---

## 회고

### 회고 1 — 저장소가 바뀌어도 LLM에게는 동일하다

시나리오 1을 InMemory와 JDBC에서 각각 실행했을 때 응답 내용이 동일했다. "그거 몇 시에 도착해요?"에서 1234를 꺼내는 방식도, 응답 문장 구조도 차이가 없었다.

LLM은 Memory가 어디에 저장됐는지 알지 못한다. `MessageChatMemoryAdvisor`가 저장소를 추상화하고 프롬프트에 대화 이력을 넣어주므로, 저장소 교체는 LLM 동작에 전혀 영향을 주지 않는다. InMemory에서 JDBC로 전환해도 A/B 테스트 없이 그냥 배포할 수 있다는 뜻이다.

---

### 회고 2 — `jdbc:h2:mem`은 재시작하면 InMemory와 똑같이 사라진다

재시작 실험에서 `jdbc:h2:mem`으로 기동한 서버를 내렸다가 올리면 세션 목록이 `[]`가 됐다. JDBC를 쓰는데도 데이터가 날아간다는 게 처음엔 이상해 보였는데, 생각해보면 당연하다. `mem:` H2는 JVM 메모리에 올라가는 인메모리 DB다. JVM이 꺼지면 DB도 같이 사라진다.

"JDBC를 쓰면 영속성이 생긴다"는 오해가 생기기 쉬운 지점이다. 영속성을 얻으려면 저장 방식(JDBC 드라이버)이 아니라 저장 위치(파일 또는 외부 DB 서버)가 바뀌어야 한다. `mem` → `file`로 URL 한 줄만 바꿔도 차이가 생긴다.

---

### 회고 3 — 파일 H2 재시작 후 고객은 서버가 내려갔는지 알 수 없다

`jdbc:h2:file`로 전환하고 서버를 완전히 내렸다가 다시 올린 뒤 같은 세션 ID로 "그거 언제 도착해요?"를 보냈더니 1234를 정확히 가리켰다. 재시작 전 응답에서 언급된 1234 맥락이 DB 파일에 그대로 남아 있었기 때문이다.

고객 입장에서는 서버가 재시작됐는지 전혀 모른다. 배포나 크래시 복구 이후에도 상담이 자연스럽게 이어지는 것이다. InMemory에서는 재시작 직후 "그거"를 물으면 "어떤 주문을 말씀하시나요?"로 되돌아간다. 이 차이가 운영 환경에서 사용자 경험에 직접 영향을 준다.

---

### 회고 4 — DB에는 TOOL 행이 없다

raw-table로 `SPRING_AI_CHAT_MEMORY`를 직접 조회하니 USER와 ASSISTANT 행만 있었다. Tool을 호출한 세션도 마찬가지였다.

`MessageChatMemoryAdvisor`가 Memory를 저장할 때 TOOL 메시지를 빼는 설계다. Tool 결과는 LLM이 ASSISTANT 응답을 만들 때 이미 소비된다. "2024-1235번 주문이 취소됐습니다"라는 ASSISTANT 응답 안에 Tool 호출의 결과가 녹아들어 있으니 지시 대명사 해결에는 충분하다.

문제가 생기는 건 감사(audit) 목적일 때다. "어떤 Tool을 어떤 파라미터로 몇 번 호출했는지" DB에서 쿼리할 방법이 없다. 이 이력이 필요하면 `PerformanceLoggingAdvisor`를 확장하거나 Tool 메서드에 직접 별도 로그를 남겨야 한다.

---

### 회고 5 — `conversation_id` 컬럼이 세션 격리의 실체다

InMemory에서 세션 격리는 `ConcurrentHashMap<String, List<Message>>`의 key가 담당한다. JDBC에서는 같은 역할을 `conversation_id` 컬럼이 한다.

raw-table을 보면 `jdbc-s1`, `jdbc-A4`, `jdbc-B4` 행들이 한 테이블에 섞여 있지만, 조회는 `WHERE conversation_id = ?`로 필터링된다. 세션이 다르면 같은 테이블 안에 있어도 서로 보이지 않는다.

이 구조가 중요한 이유는 격리 보장의 경계가 어디에 있는지 명확히 보여주기 때문이다. DB 레벨에서는 모든 세션 데이터가 한 테이블에 있다. 격리는 애플리케이션 레벨, 즉 `conversation_id` 파라미터를 올바르게 전달하는 코드 한 줄에 달려 있다. 1단계 회고에서 지적한 것과 같다. 코드가 틀려도 오류가 나지 않고 조용히 오염된다.

---

## 자가 점검 체크리스트

- [x] JDBC 프로필로 `bootRun` 성공 (`schema-h2.sql` 직접 추가로 해결)
- [x] 1단계 시나리오 5종 전체를 JDBC 프로필에서 재실행. 시나리오 1·2·3·4·5 모두 완료.
- [x] H2 Console 화면에서 `SPRING_AI_CHAT_MEMORY` 테이블의 실제 행(USER/ASSISTANT) 캡처 완료 (`docs/round3/images/h2-console-memory.png`)
- [x] 재시작 후 유지 여부가 `mem` vs `file` 조건별로 표에 기록됨
- [x] 의사결정 트리 표 작성
- [x] "InMemory 충분 조건 3개 / JDBC 필요 조건 3개" 작성
