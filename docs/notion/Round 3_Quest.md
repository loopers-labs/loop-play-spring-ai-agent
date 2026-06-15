# Round 3 숙제 Quests

# Round 3 숙제 — 대화 맥락 관리와 메모리 설계

> 🎯
이번 라운드 숙제는 **단계별 부분 제출이 가능**합니다. 막혀도 거기까지 제출하세요.
>

> 📝
이 숙제의 핵심은 Memory를 “켜는 것”이 아니라 **경계(크기 / 저장소 / 세션 수명)를 설계하는 훈련**입니다.
AI로 코드를 생성해도 됩니다. 단, **왜 그 크기·저장소·정책을 선택했는지**와 **그 경계가 무너지면 어떤 일이 생기는지**를 직접 관찰하고 기록하세요.
>

---

## 미션 제출 안내

- **제출 방식**: GitHub 레포 push + README 작성
- **단계별 부분 제출 가능**: 막힌 단계까지만 제출해도 그만큼 인정됩니다
- **제출 마감**: 다음 라운드 첫 수업 시작 전
- **핵심**: 코드보다 **설계 결정 문서 / 실패 관찰 기록**의 품질을 더 중요하게 봅니다

---

## 학습 목표 재확인

숙제를 끝내면 다음을 할 수 있어야 합니다.

- 왜 대화 메모리가 필요한가를 지시 대명사 해결, 이전 주문 참조, Tool 파라미터 유추의 세 관점에서 설명할 수 있다
- Spring AI의 `ChatMemory` / `ChatMemoryRepository` / `MessageChatMemoryAdvisor`의 역할 분리를 자기 언어로 설명할 수 있다
- `MessageWindowChatMemory`로 슬라이딩 윈도우를 구성하고, 크기 제한이 필요한 이유를 토큰 관점에서 설명할 수 있다
- HTTP 헤더와 `ChatMemory.CONVERSATION_ID`를 연결해 고객별 세션 분리를 구현할 수 있다
- InMemory와 JDBC 저장소의 선택 기준을 서버 재시작, 멀티 인스턴스, 감사 요구사항 관점에서 설명할 수 있다

---

## 사전 준비

- [ ]  **JDK 17 이상** — `java -version` 으로 확인
- [ ]  **Round 2 숙제가 완료된 상태** — Tool Calling(`OrderTools` 3개) + System Prompt + `PerformanceLoggingAdvisor`가 동작해야 함
- [ ]  Ollama 실행 중, `qwen2.5` 다운로드 완료 (`ollama list`)
- [ ]  Postman / `httpie` / `curl` — **HTTP 헤더 지정이 필수**라 헤더가 가능한 도구를 사용 (`jq` 권장)
- [ ]  IntelliJ IDEA — DEBUG 로그에서 “2회차 프롬프트에 1회차 메시지가 포함되었는지” 직접 확인

---

## 시작하기

```bash
# 1) starter-code를 본인 레포에 복사
cp -r week3/mission/starter-code ~/my-baedal-agent-week3
cd ~/my-baedal-agent-week3

# 2) Ollama 확인
ollama list  # qwen2.5

# 3) 실행 — 현 상태에서는 ChatMemoryConfig Bean이 null을 반환하므로
#    Spring Bean 생성 시 에러가 난다. 1단계 구현을 마친 후 정상 실행된다.
./gradlew bootRun

# 4) 1단계 TODO를 찾아 차례로 채운다
#    - ChatMemoryConfig.java   (4개TODO: MAX_MESSAGES + 3개 Bean)
#    - SessionController.java  (3개TODO: GET messages / DELETE / GET ids)
#    - AssistantController.java (1개TODO: @RequestHeader + Memory 연결)
#    - SupportController.java   (1개TODO: 동일 패턴)

# 5) 첫 검증
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: smoke-test" \
  -d '{"message":"2024-1234 어디쯤이에요?"}'

curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: smoke-test" \
  -d '{"message":"그거 몇 시에 도착해요?"}'

curl -s http://localhost:8080/api/v1/session/smoke-test/messages | jq
```

**Memory가 동작하지 않을 때 확인할 것:**

1. `ChatMemoryConfig`의 세 Bean이 모두 null이 아닌 실제 객체를 반환하는가?
2. `AssistantController`에 `.defaultAdvisors(memoryAdvisor, performanceAdvisor)` 순서로 등록했는가? (memoryAdvisor가 첫 번째여야 `order(10)`의 효과가 있다)
3. `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))`가 `prompt()` 체인에 있는가?
4. 같은 `X-Session-Id` 헤더 값으로 연속 호출하고 있는가? (오타 한 글자로 다른 세션이 된다)
5. `application.yml`의 `logging.level.org.springframework.ai: DEBUG`가 살아 있는가?

---

## 1단계: ChatMemory 3레이어 + X-Session-Id + 지시 대명사 시나리오

**목표**: Memory 3레이어를 직접 조립하고, `X-Session-Id`로 고객별 세션 분리가 동작하는지 5종 시나리오로 검증한다.

### 구현

- [ ]  git branch `round3`  참조
- [ ]  `ChatMemoryConfig.java`의 `// TODO [1단계-A~D]` 4개를 모두 채운다:
    - `MAX_MESSAGES` 결정 (권장 20)
    - `ChatMemoryRepository` Bean (InMemory)
    - `ChatMemory` Bean (`MessageWindowChatMemory`)
    - `MessageChatMemoryAdvisor` Bean (`order(10)`)
- [ ]  `SessionController.java`의 `// TODO [1단계-E~G]` 3개를 채운다:
    - `GET /api/v1/session/{id}/messages`
    - `DELETE /api/v1/session/{id}`
    - `GET /api/v1/session/ids`
- [ ]  `AssistantController.java`와 `SupportController.java`의 `// TODO [1단계-H, I]`에 다음을 추가:
    - `@RequestHeader(value = "X-Session-Id", defaultValue = "default")` 파라미터
    - `.defaultAdvisors(memoryAdvisor, performanceAdvisor)` (memoryAdvisor가 첫 번째)
    - `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))`

### 검증 — 시나리오 5종

아래 시나리오 5종을 실행하고, **응답 본문**과 **`/api/v1/session/{id}/messages`의 Memory 상태**와 **DEBUG 로그**를 README에 붙여라.

| # | 시나리오 | 기대 |
| --- | --- | --- |
| 1 | `A: "2024-1234 어디쯤?"` → `A: "그거 언제 도착해요?"` | 2회차에서 `getDeliveryStatus(2024-1234)` 재호출 |
| 2 | `A: "2024-1234 취소해주세요"` → `A: "아, 그거 말고 2024-1235 취소해주세요"` | 2회차에서 취소 대상이 **1235로 전환** |
| 3 | `A: "아까 물어본 그 주문 언제 도착해요?"` (1회차에서 1234 언급 후) | 이전 턴의 orderId를 Memory에서 추출 |
| 4 | 세션 오염 테스트: `A: "2024-1234..."` → `B: "그 주문 어디쯤?"` | **B에서 맥락 없음** (되묻거나 “어떤 주문을 말씀하시나요”) |
| 5 | Memory 삭제 후: `A: 2024-1234 언급` → `DELETE /api/v1/session/A` → `A: "그거"` | 삭제 이후 맥락이 사라져야 함 |

```bash
# 시나리오 1 예시
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A" \
  -d '{"message":"2024-1234 어디쯤 있어요?"}'

curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: cust-A" \
  -d '{"message":"그거 언제 도착해요?"}'

# Memory 상태 확인
curl -s http://localhost:8080/api/v1/session/cust-A/messages | jq

# 등록된 세션 목록
curl -s http://localhost:8080/api/v1/session/ids | jq
```

### 설계 결정 문서 (README에 작성)

- [ ]  `MAX_MESSAGES`를 **N으로 선택한 근거**를 쓰라. 배달 상담의 평균 턴 수를 가정(예: 고객이 3~6턴을 주고받는다)하고 계산으로 증명. “20이면 USER/ASSISTANT를 합쳐 약 ? 턴”을 적는 수준이면 충분
- [ ]  `X-Session-Id` 헤더가 없을 때 `"default"`로 폴백하는 정책의 **구체적 위험 시나리오**를 2개 이상 제시 (힌트: 앱 업데이트를 안 한 구버전 클라이언트 / 어뷰저가 의도적으로 헤더를 뺀 경우)
- [ ]  세션 식별의 실무 대안(**쿠키 / JWT 클레임 / URL 경로 / HTTP 헤더**) 4가지를 표로 비교. 각각 **배달 상담 도메인** 적용 시 장단점
- [ ]  세션 ID를 **클라이언트가 직접 정하게 하는 방식**의 보안 리스크는? 어떻게 막겠는가? (힌트: 서버 발급 / UUID / JWT 서명 검증)

### 자가 점검 체크리스트

- [ ]  `./gradlew bootRun`으로 프로젝트가 정상 실행되는가? (`UnsupportedOperationException` 없어야 함)
- [ ]  시나리오 5종의 응답 본문 + Memory 상태 JSON이 모두 README에 있는가?
- [ ]  시나리오 4에서 **세션 B에 맥락이 없음**을 `/api/v1/session/B/messages`로 증명했는가?
- [ ]  시나리오 5에서 `DELETE` 후 Memory가 비어 있음을 증명했는가?
- [ ]  `MAX_MESSAGES` 선택 근거 + 세션 ID 설계 결정 4개 질문의 “왜?” 답이 README에 있는가?

---

## 2단계: Memory 크기 실험 + 실패 관찰

**목표**: `MAX_MESSAGES`를 바꿔 가며 같은 질문 시퀀스를 돌리고, 입력 토큰 / 정확도를 정량 비교한다.

### 구현

`ChatMemoryConfig`의 `MAX_MESSAGES`를 **3가지 값**으로 번갈아 바꾸면서 다음 시퀀스를 각각 실행하라.

| 실험 | MAX_MESSAGES | 목적 |
| --- | --- | --- |
| A | `20` (기준) | 정상 케이스 |
| B | `2` (극단적으로 작게) | **지시 대명사 해결 실패** 관찰 |
| C | `Integer.MAX_VALUE` (사실상 무제한) | 입력 토큰의 선형 증가 관찰 |

각 실험마다 **동일한 10턴 시나리오**를 실행한다:

```
1) "2024-1234 배달 상황 알려주세요"
2) "그거 몇 분 남았어요?"
3) "2024-1235 주문도 있는데 메뉴 뭐였죠?"
4) "아 그 버거 세트"
5) "2024-1234 취소 가능해요?"
6) "그럼 1235는 취소되죠?"
7) "그거 취소해주세요"        ← "그거"는 1235 (직전 언급)
8) "아까 1234는 언제 도착해요?"
9) "그 주문 라이더 위치 다시 확인"
10) "요약해 주세요 지금까지 제가 뭘 물어봤는지"
```

### 정량 비교 표 (README에 작성)

| 실험 | MAX_MESSAGES | 10턴 평균 입력 토큰 | 10턴 평균 출력 토큰 | 10턴 평균 응답 시간 | 지시 대명사 해결 성공 / 10 |
| --- | --- | --- | --- | --- | --- |
| A | 20 | ? | ? | ? | ? |
| B | 2 | ? | ? | ? | ? |
| C | MAX_VALUE | ? | ? | ? | ? |
- 입력 토큰은 `PerformanceLoggingAdvisor`의 로그 (`입력 토큰: YY`)를 읽어 평균을 낸다
- “지시 대명사 해결 성공”은 7번 / 8번 / 9번처럼 “그거 / 아까 그” 등이 올바른 orderId로 치환된 횟수

### 실패 관찰 — MAX_MESSAGES = 2 의 파괴적 결과 (README에 작성)

> ⚠️
형식적인 “안 됐어요”가 아니라, **시스템이 어떻게 망가지는지** 출력 자체를 그대로 기록하는 것이 핵심입니다.
>
- [ ]  실험 B에서 **지시 대명사가 엉뚱하게 해석된 턴**을 최소 1개 캡처 (요청 + LLM 응답 + Memory 상태 JSON)
- [ ]  “왜 2는 부족한가”를 **USER/ASSISTANT 메시지 구조** 기준으로 설명 (힌트: 2개면 직전 USER/ASSISTANT 한 쌍밖에 안 남음)
- [ ]  실험 C에서 **응답 시간이 A 대비 어느 턴부터 눈에 띄게 느려지는지** 관찰

### 설계 결정 문서

- [ ]  `MessageWindowChatMemory`는 슬라이딩 윈도우 전략이다. **대화 요약(summarization) 전략**은 어떤 시나리오에 맞는가? 장단점 표 작성
- [ ]  “지시 대명사 해결 성공률”이 **10턴 중 몇 회 미만이면 프로덕션에 올리면 안 되는가**? 본인이 정한 기준 + 근거
- [ ]  배달 상담 도메인에서 “오래된 대화”가 의미 있는 경우는? (예: 3시간 전 주문 건을 다시 묻는 경우 / 어제 환불한 건 재문의 등)
- [ ]  만약 Memory를 **세션이 아닌 고객 단위로 영속 유지**한다면 어떤 새로운 기능이 가능하고, 어떤 리스크가 있는가?

### 자가 점검 체크리스트

- [ ]  3가지 `MAX_MESSAGES` 값에 대한 정량 비교 표가 있는가?
- [ ]  실험 B에서 지시 대명사 해결 실패 예시가 1개 이상 캡처되어 있는가?
- [ ]  실험 C의 “입력 토큰이 선형 증가”하는 흐름이 수치로 기록되어 있는가?
- [ ]  요약 전략의 장단점 + “몇 회 미만 실패 시 프로덕션 금지” 기준이 작성되어 있는가?

---

## 3단계: InMemory vs JdbcChatMemory — 의사결정 트리

**목표**: JDBC 저장소로 전환하고, 어떤 운영 조건에서 어느 저장소가 정답인지 의사결정 트리를 직접 그린다.

### 구현

- [ ]  `build.gradle`에 다음 의존성을 추가:

   ```groovy
   implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc'
   runtimeOnly    'com.h2database:h2'
   ```

- [ ]  `ChatMemoryConfig`의 `chatMemoryRepository()` Bean을 `@Profile("!jdbc")`로 한정 (JDBC 프로필에서는 자동 구성된 `JdbcChatMemoryRepository`가 주입되도록)
- [ ]  `application-jdbc.yml`은 이미 starter-code에 있다. `spring.profiles.active=jdbc`로 실행:

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=jdbc'
   ```

- [ ]  1단계의 시나리오 5종을 **JDBC 프로필에서 다시 돌려라**. 응답이 동일해야 한다
- [ ]  H2 Console(`http://localhost:8080/h2-console`)에 접속하여 아래 쿼리 결과를 캡처해 README에 붙여라:

   ```sql
   SELECT * FROM SPRING_AI_CHAT_MEMORY ORDER BY "timestamp";
   ```


### 재시작 실험 (영속성 검증)

- [ ]  `h2:mem` 인 상태로 대화 → 애플리케이션 종료 → 다시 `bootRun` → 같은 세션 ID로 “그거” 질문 → Memory가 살아남았는가?
- [ ]  URL을 `h2:file:./data/baedal` 으로 바꾸고 동일 실험 반복. 무엇이 달라지는가?
- [ ]  관찰 결과를 표로 정리:

| 저장소 설정 | 재시작 후 Memory 유지? | 비고 |
| --- | --- | --- |
| InMemory (기본) | ? |  |
| `jdbc:h2:mem:...` | ? | JVM 생존 동안만 유지 |
| `jdbc:h2:file:...` | ? | 파일 기반 영속 |

### 설계 결정 문서 — 의사결정 트리 (README에 작성)

아래 질문 체크리스트에 대한 **답변 + 그때 선택할 저장소**를 표로 작성하라.

| 운영 조건 | Yes/No | 선택 |
| --- | --- | --- |
| 서비스가 로드밸런서 뒤 멀티 인스턴스로 뜨는가? |  |  |
| 서버 재시작 후에도 고객 대화가 이어져야 하는가? |  |  |
| 법적/감사 이유로 상담 이력을 N년 보관해야 하는가? |  |  |
| 단일 인스턴스 + 세션이 분 단위로 짧은가? |  |  |
- [ ]  **언제 InMemory로 충분한가**를 3가지 조건으로 정리
- [ ]  **언제 JDBC가 필요한가**를 3가지 조건으로 정리
- [ ]  배달 실제 운영이라면 어떤 DB를 선택하겠는가? (PostgreSQL / MySQL / Redis / DynamoDB 중) — 근거는? (힌트: Round 4에서 PgVector를 띄운다는 점을 고려)
- [ ]  JDBC 저장소를 도입할 때 **동시에 고려해야 할 비기능 요구사항** 3가지 (백업 / 인덱스 / 파티셔닝 / TTL / 암호화 등)

### 자가 점검 체크리스트

- [ ]  JDBC 프로필로 bootRun이 성공하는가?
- [ ]  H2 Console에서 `SPRING_AI_CHAT_MEMORY` 테이블의 실제 행(USER/ASSISTANT/TOOL)이 README에 캡처되어 있는가?
- [ ]  재시작 후 유지 여부가 `mem` vs `file` 조건별로 표에 기록되어 있는가?
- [ ]  의사결정 트리 표 + “InMemory 충분 조건 3개 / JDBC 필요 조건 3개”가 작성되어 있는가?

---

## 4단계: Observability + AI 코드 리뷰

**목표**: Memory가 프롬프트에 끼어드는 모습을 로그로 직접 관찰하고, AI가 만든 메모리 코드의 프로덕션 결함을 검토한다.

### 구현 — `PerformanceLoggingAdvisor`로 Memory 크기에 따른 토큰 증가 관찰

- [ ]  2단계의 10턴 시나리오를 `MAX_MESSAGES = 20` 기준으로 다시 돌리고, **매 턴의 입력 토큰**을 표로 기록:

| 턴 | 입력 토큰 | 출력 토큰 | 응답 시간(ms) |
| --- | --- | --- | --- |
| 1 | ? | ? | ? |
| 2 | ? | ? | ? |
| … |  |  |  |
| 10 | ? | ? | ? |
- [ ]  **1턴과 10턴의 입력 토큰 차이가 몇 배인지** 계산. 이 증가가 어디서 오는지(Memory에 쌓인 이전 메시지 + Tool 스키마) 구체적으로 설명
- [ ]  시나리오 3번(`"2024-1234 주문 메뉴 뭐였죠?"` → `"그 메뉴 배달 언제?"`)에서:
    - 1회차 DEBUG 로그: 사용자 메시지만 포함된 프롬프트
    - 2회차 DEBUG 로그: **1회차의 USER + ASSISTANT 메시지가 프롬프트 앞에 추가**된 모습
    - 두 프롬프트의 전문을 README에 붙여 “Memory가 프롬프트 조립 시점에 끼어드는 것”임을 증명

### AI 코드 리뷰 — 프로덕션 결함 찾기 (README에 작성)

1. AI(ChatGPT, Claude, Cursor 등)에게 아래 프롬프트로 코드를 요청하라:
> `"Spring AI 1.0으로 배달 챗봇에 대화 메모리 기능을 붙여줘. 세션별로 대화가 유지되어야 해."`
2. 받은 코드에서 **프로덕션에 올리면 안 되는 결함 3개**를 찾아 기록 (아래 힌트 중 3개 이상 해당)
    - **동시성 문제**: 세션 저장소가 `HashMap` (ConcurrentHashMap 아님) — thread-unsafe
    - **메모리 누수**: 세션 TTL/최대 크기 제한 없음 → 세션이 무한 적재
    - **세션 오염**: 세션 ID를 따로 구분 없이 공용으로 씀 → 고객 간 대화 혼선
    - **프라이버시**: 전화번호/주소 등 민감 정보가 평문 저장
    - **Memory 크기 제한 없음**: `MessageWindow` 미사용 → 입력 토큰 선형 증가
    - **재시작 시 소실**: InMemory만 쓰고 영속화 옵션 미제공
    - **세션 식별 누락**: `conversationId`를 고정값으로 하드코딩
    - **멀티 인스턴스 미고려**: 로컬 Map만 사용 → 로드밸런서 뒤에서 깨짐
3. 각 결함마다 **이번 라운드에서 배운 방식으로 어떻게 고칠지** 개선 방안 작성
   (예: “`MessageWindowChatMemory` 사용 + `maxMessages(20)` 설정으로 크기 제한”, “`JdbcChatMemoryRepository` + 90일 TTL 배치”)
4. AI가 생성한 원본 코드와 본인의 개선 코드를 함께 README에 첨부

### 자가 점검 체크리스트

- [ ]  10턴 입력 토큰 증가 표 + 1턴 대비 10턴 배수 계산이 README에 있는가?
- [ ]  Memory가 붙은 2회차 프롬프트 전문이 캡처되어 있는가? (USER/ASSISTANT 메시지 포함)
- [ ]  AI 생성 코드의 원본이 README에 첨부되어 있는가?
- [ ]  결함 3개 + 각각의 개선 방안(수업 내용과 연결)이 구체적으로 쓰여 있는가?

---

## 공통: 학습 기록

README 하단에 다음 세 단락을 작성하라:

- [ ]  **“내가 배운 것”** — Round 3에서 새롭게 알게 된 점 (Memory 3레이어 / 슬라이딩 윈도우 / 세션 식별 / InMemory↔︎JDBC 교체 / Memory와 Tool의 상호작용 등 중 **본인이 직접 체감한 것** 위주로)
- [ ]  **“의문점”** — 아직 해결되지 않은 궁금증
  (예: “Memory에 Tool 응답이 포함될 때와 안 될 때 LLM의 행동이 어떻게 달라지는가?” “요약 전략을 직접 구현한다면 언제 요약할지 어떻게 판단하는가?”)
- [ ]  **“Round 4에 시도하고 싶은 것”** — Chat Memory와 RAG를 연결할 아이디어
  (예: “Memory는 ‘그 주문’ 같은 세션 맥락, RAG는 ‘배달 지연 보상 정책’ 같은 지식. 두 Advisor가 체인에 같이 붙으면 어떤 질문을 커버할 수 있을까?”)

---

## 제출 가이드

1. 본인 GitHub 레포에 push
2. README.md에 다음을 모두 포함:
    - 1단계 시나리오 5종 응답 + Memory 상태 JSON + 설계 결정 문서
    - 2단계 정량 비교 표 (3가지 `MAX_MESSAGES` 값) + 실패 관찰 캡처
    - 3단계 H2 Console 쿼리 결과 + 재시작 실험 표 + 의사결정 트리
    - 4단계 10턴 토큰 증가 표 + Memory 포함된 2회차 프롬프트 전문 + AI 코드 리뷰 (원본 + 개선 코드)
    - 공통: “내가 배운 것 / 의문점 / Round 4 아이디어”
3. 다음 라운드 첫 수업 전까지 PR 또는 레포 링크 제출

### PR/제출물 체크

- [ ]  제목: `[Round 3] {본인 이름} - {몇 단계까지 완료}`
- [ ]  본문에 어디까지 완료했고 어디서 막혔는지 명시
- [ ]  API Key 등 민감 정보가 커밋에 포함되지 않았는지 확인
- [ ]  `./gradlew build` 로 컴파일 에러 없는지 마지막으로 확인
- [ ]  **변경 파일 20개 이하** — 넘으면 `.gitignore`에 `build/`, `.gradle/`, `.class`, `.idea/`, `.iml` 추가
- [ ]  본인 PR을 본인이 한 번 셀프 리뷰 — “위험 신호” 7가지에 걸리는지 확인

### Merge 조건

- **페어 리뷰어 1명 이상의 Approve** 가 필요합니다
- Approve 기준은 3축: 설계 결정의 근거 / 실패 관찰의 구체성 / 다음 라운드 연결
- **Round 3에서 리뷰어가 특히 보는 3가지**: (1) `MAX_MESSAGES` 양 옆 값(2 / 20 / MAX_VALUE) 정량 비교 (2) 세션 분리 누락 시 다른 고객 대화가 새는 실패 시뮬레이션 (3) InMemory vs JDBC 의사결정 트리
- 페어 리뷰어로 배정되면 **48시간 내 첫 코멘트**, 다음 라운드 첫 수업 24시간 전까지 결론
    - 요거 어렵죠…!?

> 💡
Round 3는 **보안 사고 시뮬레이션**이 처음 등장하는 라운드입니다. 세션 분리 누락이 “테스트는 통과하고 운영에서 발견되는” 사고임을 직접 만든 케이스가 있다면, 페어 리뷰어가 가장 흥미롭게 볼 지점입니다.
>