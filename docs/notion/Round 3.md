# Round 3 — 대화 맥락 관리와 메모리 설계

# Round 3 — 대화 맥락 관리와 메모리 설계

## 이번 라운드에 배우는 것

Round 2까지 만든 에이전트는 **한 턴짜리 상담**만 잘합니다. 하지만 배달 상담은 거의 모든 대화가 다음 같은 꼴입니다.

```
고객: 2024-1234 주문 지금 어디쯤 있어요?
 봇 : 라이더가 역삼역 사거리 부근에 있습니다.
고객: 그럼 그거 취소해주세요.
 봇 : ???   ← "그거"가 뭐지?
```

세 번째 발화에서 LLM이 뭔가 하려면 **이전 대화를 기억**해야 합니다. Round 3은 그 “기억”을 만드는 라운드입니다.

- Spring AI의 `ChatMemory` / `ChatMemoryRepository` / `MessageChatMemoryAdvisor` 3레이어를 직접 조립한다
- `MessageWindowChatMemory`로 슬라이딩 윈도우를 구성하고, 왜 크기 제한이 필요한지 토큰 관점에서 이해한다
- HTTP 헤더(`X-Session-Id`)와 `ChatMemory.CONVERSATION_ID` 파라미터로 **고객별 세션 분리**를 구현한다
- **InMemory와 JDBC 저장소의 선택 기준**을 서버 재시작 / 멀티 인스턴스 / 감사 요구사항 관점에서 판단한다

> 🎯
**이번 라운드의 한 줄 메시지**: **대화 메모리는 “있으면 좋은 기능”이 아니라 상담 에이전트의 전제 조건이다.**
Memory 없는 에이전트는 사실상 단발 챗봇일 뿐입니다.
>

---

## 학습 목표

이번 라운드가 끝나면 다음을 할 수 있습니다.

- [ ]  **왜 대화 메모리가 필요한가**를 지시 대명사 해결, 이전 주문 참조, Tool 파라미터 유추의 세 관점에서 설명할 수 있다
- [ ]  Spring AI의 `ChatMemory` / `ChatMemoryRepository` / `MessageChatMemoryAdvisor`의 역할 분리를 자기 언어로 설명할 수 있다
- [ ]  `MessageWindowChatMemory`로 슬라이딩 윈도우를 구성하고, 크기 제한이 필요한 이유를 토큰 관점에서 설명할 수 있다
- [ ]  HTTP 헤더와 `ChatMemory.CONVERSATION_ID`를 연결해 고객별 세션 분리를 구현할 수 있다
- [ ]  **InMemory와 JDBC 저장소의 선택 기준**을 서버 재시작, 멀티 인스턴스, 감사 요구사항 관점에서 설명할 수 있다
- [ ]  Tool Calling과 Memory가 같은 세션 안에서 어떻게 상호작용하는지 로그로 관찰하고 설명할 수 있다

---

## 1부. 왜 Memory가 필요한가 — 대화는 한 번이 아니다

### 1.1 Round 1·2까지의 한계

지금까지 만든 에이전트는 한 턴짜리 상담만 잘합니다. 다음 대화에서 LLM이 뭘 해야 할까요?

```
고객: 2024-1234 주문 지금 어디쯤 있어요?
 봇 : (getDeliveryStatus 호출) 라이더가 역삼역 사거리 부근에 있습니다.
고객: 아 그거 말고 2024-1235는요?
 봇 : (getDeliveryStatus 호출) 아직 조리 시작 전입니다.
고객: 그럼 그거 취소해주세요.
 봇 : ???  ← "그거"가 뭐지?
```

배달 상담은 거의 모든 대화가 이런 꼴입니다. 주문번호를 한 번 말한 뒤에는 고객이 다시 말해주지 않아요.

> 🎯
**핵심 메시지**: 대화 메모리는 “있으면 좋은 기능”이 아니라 **상담 에이전트의 전제 조건**이다. Memory 없는 에이전트는 사실상 ’단발 챗봇’일 뿐이다.
>

### 1.2 Memory가 해결하는 세 가지 문제

| 문제 | 예시 | Memory 없으면 |
| --- | --- | --- |
| **지시 대명사 해결** | “그거 취소해줘” / “방금 그 주문” / “아까 말한 거” | LLM이 orderId를 알 수 없음 → 되묻거나 환각 |
| **이전 주문 참조** | “아까 물어본 주문, 환불 되나요?” | Tool 호출 시 orderId를 빈 값으로 넘기거나 포기 |
| **Tool 호출 파라미터 유추** | “라이더 어디쯤?” (이전에 2024-1234 물어봄) | `getDeliveryStatus` 호출 자체가 안 됨 |

Round 2에서 우리는 Tool 호출을 배웠습니다. 그 Tool이 **어떤 orderId로 호출될지**가 바로 이 문제입니다. Memory가 이걸 해결해 줍니다 — LLM이 이전 턴의 문맥에서 orderId를 스스로 꺼내 쓰도록.

### 1.3 Memory 없이 흉내내면 안 되는가

개발자 반응 1: “그냥 `Map<sessionId, List<Message>>` 하나 두고 내가 직접 관리하면 되지 않나요?”

원칙적으로는 맞습니다. 그런데 그건 곧 다음을 직접 만드는 일입니다.

- 메시지를 **프롬프트에 어떤 포맷으로 끼워 넣을지** (역할 태그, 순서, 중복 제거)
- 메시지가 **몇 개까지 쌓이면 잘라낼지** (토큰 예산 관리)
- Tool 호출 메시지도 남길지, **사용자 턴만 남길지**
- 새 메시지를 저장하는 **시점**이 호출 전인가 후인가
- Streaming 응답의 경우 **언제 저장**할 것인가

Spring AI의 `ChatMemory`와 `MessageChatMemoryAdvisor`가 이걸 이미 해결해 둔 라이브러리입니다. 바퀴를 다시 만들 필요는 없죠.

### 1.4 Spring AI의 Memory 구성 요소

Spring AI 1.0에서 Memory는 **세 레이어**로 나뉘어 있습니다.

| 구성 요소 | 역할 | 비유 |
| --- | --- | --- |
| `ChatMemoryRepository` | 메시지의 **저장소** (CRUD) | JPA Repository |
| `ChatMemory` | 저장소 위의 **정책 레이어** (크기 제한, TTL 등) | Service 계층 |
| `MessageChatMemoryAdvisor` | ChatClient 호출 흐름에 Memory를 연결하는 **어댑터** | Spring MVC 인터셉터 |

> 💡
왜 굳이 Repository와 Memory를 분리했는가? **저장 기술(InMemory / JDBC / Redis)** 과 **크기 제어 정책(최근 N개 / 요약 / TTL)** 이 독립적으로 변하기 때문이다. 둘 다 바꿀 수 있도록 인터페이스를 쪼개 둔 것이다.
>

```jsx
Client 에서 대화 내역을 알고 있음.
메세지를 주고받으니까.

그래서 Server 한테 보낼때 누적해서 발송.
ChatMemory 가 필요 X
```

---

## 2부. `ChatMemory` 3구성요소 — Repository / Memory / Advisor

### 2.1 ChatMemoryRepository — 그냥 CRUD다

`ChatMemoryRepository`는 메시지를 **어디에 저장할지**를 결정합니다. 인터페이스는 놀랄 만큼 단순합니다.

```java
public interface ChatMemoryRepository {
    List<String>  findConversationIds();
    List<Message> findByConversationId(String conversationId);
    void          saveAll(String conversationId, List<Message> messages);
    void          deleteByConversationId(String conversationId);
}
```

- 교육용으로는 `InMemoryChatMemoryRepository`로 충분합니다 (`ConcurrentHashMap` 기반).
- 실제 서비스에서는 `JdbcChatMemoryRepository` (자동 구성, 4부에서 상세), `CassandraChatMemoryRepository`, `RedisChatMemoryRepository` 등이 있습니다.
- 직접 만든다면 위 4개 메서드만 구현하면 됩니다.

### 2.2 ChatMemory — 크기 제어 정책

저장소는 “메시지를 쌓는 것”만 알지, “얼마나 쌓아둘지”는 모릅니다. 그 정책이 `ChatMemory` 레이어입니다.

```java
public interface ChatMemory {
    String DEFAULT_CONVERSATION_ID = "default";
    String CONVERSATION_ID = "chat_memory_conversation_id";

    void add(String conversationId, List<Message> messages);
    List<Message> get(String conversationId);
    void clear(String conversationId);
}
```

- `MessageWindowChatMemory`: **최근 N개** 메시지만 유지. 오래된 건 자동으로 잘라낸다.
- 대화 요약 기반(Summarization)은 Spring AI 1.0 시점엔 기본 제공되지 않는다. 필요하면 `ChatMemory`를 직접 구현한다.

> 💡
**왜 크기 제한이 필수인가** — LLM 입력 토큰은 선형으로 과금된다. 100턴짜리 대화를 무제한으로 쌓으면 100번째 호출은 1번째보다 입력이 100배 이상 커진다. 비용도, 응답 시간도 감당이 안 된다.
>

### 2.3 MessageChatMemoryAdvisor — 흐름에 끼워 넣기

앞의 두 레이어만으로는 ChatClient가 Memory를 **자동으로** 읽고 쓰지 않습니다. Advisor가 이 일을 합니다.

- **Before**: 사용자 요청을 받기 직전, `chatMemory.get(conversationId)`를 호출해 이전 메시지를 불러와 프롬프트 앞에 끼워 넣는다.
- **After**: LLM 응답을 받은 직후, 사용자 메시지와 Assistant 응답을 모두 `chatMemory.add(conversationId, ...)`로 저장한다.

Spring MVC의 필터 체인과 같은 구조입니다. ChatClient는 여러 Advisor를 체인으로 구성할 수 있습니다 — Round 2의 `PerformanceLoggingAdvisor`와 Round 3의 `MessageChatMemoryAdvisor`가 공존합니다.

### 2.4 설정 코드 — `ChatMemoryConfig`

세 레이어를 한 파일에 모아 둡니다.

- ChatMemoryConfig 전체 코드 보기

    ```java
    @Configuration
    public class ChatMemoryConfig {
    
        private static final int MAX_MESSAGES = 20;
    
        @Bean
        public ChatMemoryRepository chatMemoryRepository() {
            return new InMemoryChatMemoryRepository();
        }
    
        @Bean
        public ChatMemory chatMemory(ChatMemoryRepository repository) {
            return MessageWindowChatMemory.builder()
                    .chatMemoryRepository(repository)
                    .maxMessages(MAX_MESSAGES)
                    .build();
        }
    
        @Bean
        public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
            return MessageChatMemoryAdvisor.builder(chatMemory)
                    .order(10)
                    .build();
        }
    }
    ```


> 💡
**`order(10)`의 의미** — Advisor는 여러 개가 체인으로 연결됩니다. `order` 값이 낮을수록 먼저 실행됩니다. Memory Advisor는 Performance Advisor보다 먼저(=프롬프트 조립 시점에) 동작해야 하므로 낮은 값을 줍니다.
>

### 2.5 ChatClient에 연결

`AssistantController`에 한 줄만 추가합니다.

```java
return builder
        .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
        .defaultAdvisors(memoryAdvisor, performanceAdvisor)  // Round 3: memoryAdvisor 추가
        .defaultTools(orderTools)
        .build()
        .prompt()
        .user(req.message())
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))  // 세션 지정
        .call()
        .content();
```

`.advisors(a -> a.param(...))` 가 핵심입니다. 이 호출에 한해 “어떤 세션의 Memory를 쓸 것인가”를 지정합니다.

> ⚠️
**Round 2에서 배운 빌더 누적 함정**을 잊지 마세요. 핸들러 메서드 안에서 매 요청마다 `.defaultAdvisors(...)`를 호출하면 Advisor가 누적 등록됩니다. 컨트롤러 단위 고정 설정은 생성자에서 한 번만 build해 `ChatClient`로 보관하세요.
>

### 2.6 라이브 데모 — Memory가 실제로 하는 일 보기

```bash
# 1) 주문번호 언급
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: live-demo" \
  -d '{"message":"2024-1234 배달 상황 알려주세요"}'

# 2) "그거" — 지시 대명사
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: live-demo" \
  -d '{"message":"그거 몇 분 남았어요?"}'

# 3) 저장된 메시지 직접 확인
curl -s http://localhost:8080/api/v1/session/live-demo/messages | jq
```

**관찰 포인트:**

| 순서 | 콘솔 로그에서 찾아야 할 것 |
| --- | --- |
| 1회차 | `USER: "2024-1234 ..."` → `[Tool] getDeliveryStatus(2024-1234)` → `ASSISTANT: ...` |
| 2회차 | **1회차 메시지가 프롬프트 앞에 포함됨** → `[Tool] getDeliveryStatus(2024-1234)` 재호출 → Assistant 응답 |
| Memory 조회 | USER × 2, ASSISTANT × 2 (Tool 메시지는 구현에 따라 다름) |

> 🎯
**체크포인트**: 2회차 DEBUG 로그에서 프롬프트 길이가 1회차보다 길어졌는지 눈으로 확인하세요. Memory가 작동하면 반드시 입력 토큰 수가 증가합니다. 그게 Memory의 정체입니다.
>

---

## 3부. 세션 관리 — `X-Session-Id` 헤더와 `conversationId`

### 3.1 왜 세션 식별이 중요한가

Memory는 세션 ID별로 분리 저장됩니다. 만약 모든 사용자가 같은 `conversationId`를 공유하면:

- 내 대화가 다른 고객에게 노출된다 (심각한 개인정보 사고)
- LLM이 **다른 고객의 주문번호**를 참조해 엉뚱한 Tool을 호출한다

세션 식별은 보안 이슈입니다. 장난이 아닙니다.

### 3.2 세션 식별 전략들

| 전략 | 특징 | 배달 상담 적합성 |
| --- | --- | --- |
| 쿠키 / HTTP Session | 웹 브라우저 친화, 서버 Sticky Session 필요 | 웹 챗봇 UI면 OK |
| **HTTP 헤더 (`X-Session-Id`)** | 클라이언트가 직접 관리, 모든 클라이언트 환경에 통용 | **앱/웹/API 고객 모두 지원** |
| JWT 클레임 | 인증과 함께 세션 식별 | 이미 JWT 인증이 있다면 권장 |
| URL 경로 (`/session/{id}/chat`) | 명시적이지만 URL이 길어짐 | 운영 API로는 비추천 |

이번 라운드에서는 **HTTP 헤더 방식**을 씁니다. 가장 범용적이며, 프레임워크 독립적이고, `@RequestHeader`로 간단히 받을 수 있습니다.

### 3.3 구현

```java
@PostMapping
public String ask(@RequestBody ChatRequest req,
                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

    return chatClient.prompt()
            .user(req.message())
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
            .call()
            .content();
}
```

- `defaultValue = "default"`: 헤더가 없으면 공용 세션으로 폴백. **개발용으로만 허용하고, 프로덕션에선 400 Bad Request를 내려야 한다**.
- `ChatMemory.CONVERSATION_ID`는 단순 문자열 상수 (`"chat_memory_conversation_id"`)다. 직접 문자열을 넣어도 동작하지만, 타입 안전성을 위해 상수를 쓰는 게 맞다.

### 3.4 라이브 데모 — 세션 A vs 세션 B

두 창을 띄워 동시에 실행합니다.

```bash
# 세션 A: 2024-1234 이야기
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: customer-A" \
  -d '{"message":"2024-1234 지금 어디쯤이에요?"}'

# 세션 B: 2024-1239 이야기
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: customer-B" \
  -d '{"message":"2024-1239 주문 취소해주세요"}'

# 다시 세션 A: "그거" — 어느 주문을 가리킬까?
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: customer-A" \
  -d '{"message":"아 그거 말고 다시 확인해주세요"}'
```

**기대:**

- 세션 A의 세 번째 발화에서 “그거”는 **2024-1234**로 해석되어야 함 (2024-1239 아님)
- 두 세션이 **서로 오염되지 않음**

```bash
# 등록된 세션 목록 확인
curl -s http://localhost:8080/api/v1/session/ids | jq
# → ["customer-A", "customer-B"]
```

> 🎯
**체크포인트**: 같은 서버 프로세스에서 두 세션이 **분리 저장**되는 걸 세션 ID 목록으로 확인했다면 세션 설계는 성공입니다. 만약 세션 A의 대화에서 1239 주문이 언급된다면 헤더 처리가 잘못된 것입니다.
>

### 3.5 지시 대명사 해결의 대표 시나리오

수업 중에는 아래 시퀀스를 반드시 돌려 봅니다.

```
고객: 주문번호 2024-1234 취소해주세요
 봇 : (cancelOrder 호출 시도 → COOKING이므로 NOT_CANCELABLE) ...

고객: 아, 그거 말고 2024-1235 취소해주세요
 봇 : (cancelOrder("2024-1235") 호출 → CREATED → CANCELED) 취소 완료

고객: 아니 아까 그거, 상담원 연결도 같이요
 봇 : 2024-1234 건으로 상담원 연결 안내를 드리겠습니다
```

**관찰**: 마지막 발화의 “아까 그거”는 2024-1235가 아닌 **2024-1234**. 시스템 프롬프트의 “가장 마지막에 언급된 주문번호”보다 “가장 맥락상 맞는 것”을 LLM이 선택하는 경우가 종종 있습니다. Qwen2.5는 이 해석이 80% 정도 정확합니다 — 숙제에서 실패 케이스를 관찰합니다.

### 3.6 세션의 생명주기

- **만들기**: 첫 요청이 오면 자동으로 생성 (Repository에 conversationId가 없으면 add 시점에 생성)
- **유지**: 지정된 세션 ID로 연속 요청이 오는 동안
- **삭제**: 명시적으로 `chatMemory.clear(sessionId)` 호출 시 (또는 InMemory면 서버 재시작 시)

배달 운영 관점에서 세션은 어떻게 끝나야 할까? 정답은 없습니다. “주문 완료 후 10분”, “상담원 연결 시 자동 클리어” 등 정책이 필요합니다.

```java
// 데모용 삭제 엔드포인트
@DeleteMapping("/{sessionId}")
public void clear(@PathVariable String sessionId) {
    chatMemory.clear(sessionId);
}
```

---

## 4부. InMemory vs JDBC — 저장소는 언제 바꿔야 하는가

### 4.1 InMemory의 한계

`InMemoryChatMemoryRepository`는 `ConcurrentHashMap` 기반입니다. 충분히 빠르고, 테스트하기 쉽고, 설정이 0입니다. 단점은 전부 운영 관점에서 나옵니다.

| 한계 | 실제 영향 |
| --- | --- |
| **서버 재시작 시 소실** | 배포 한 번에 모든 상담 이력이 사라진다 |
| **멀티 인스턴스 불가** | 로드밸런서 뒤에 서버가 2대면, 2번째 요청이 다른 서버로 가면 기억이 없다 |
| **감사(audit) 불가** | “고객이 그날 봇한테 뭐라고 했는가”를 사후에 추적할 수 없다 |
| **메모리 폭증 위험** | 세션이 계속 쌓이면 JVM Heap이 증가. TTL이 없다 |

### 4.2 JDBC로 가야 할 때

다음 중 하나라도 해당되면 **InMemory로는 부족**합니다.

- [ ]  서비스가 멀티 인스턴스로 배포된다 (로드밸런서 뒤)
- [ ]  사용자가 앱을 껐다 켜도 대화가 이어져야 한다
- [ ]  고객 상담 이력을 **법적/감사 이유로 N년 보관**해야 한다
- [ ]  서버 재시작이 주말마다 배포로 일어난다
- [ ]  운영팀이 “어떤 고객이 어떤 말을 했나”를 DB에서 조회하고 싶어 한다

> 🎯
**핵심 메시지**: InMemory는 **단일 인스턴스 + 단기 세션** 전제 하에서만 정답이다. 이 전제를 깨는 순간 자료 손실과 UX 파손이 즉시 일어난다.
>

### 4.3 Spring AI 1.0의 JDBC 설정

Spring AI 1.0은 `JdbcChatMemoryRepository`를 자동 구성합니다. 단, 의존성을 추가해야 합니다.

```groovy
// build.gradle (주석 해제 시)
implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc'
runtimeOnly    'com.h2database:h2'   // 또는 PostgreSQL 드라이버
```

```yaml
# application-jdbc.yml
spring:
datasource:
url: jdbc:h2:mem:baedal;MODE=PostgreSQL
username: sa
ai:
chat:
memory:
repository:
jdbc:
initialize-schema: embedded   # always / never / embedded
```

**실행:**

```bash
./gradlew bootRun --args='--spring.profiles.active=jdbc'
```

**스키마 (자동 생성):**

```sql
CREATE TABLE SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL,  -- USER / ASSISTANT / SYSTEM / TOOL
    "timestamp"     TIMESTAMP   NOT NULL
);
CREATE INDEX ... ON SPRING_AI_CHAT_MEMORY (conversation_id, "timestamp");
```

> 💡
**왜 starter 교체만으로 되는가** — 자동 구성이 `ChatMemoryRepository` Bean을 이미 등록하기 때문. 우리가 `ChatMemoryConfig`에 만든 InMemory Bean은 제거하거나 `@Profile("!jdbc")`로 한정해야 충돌이 없습니다.
>

### 4.4 프라이버시 — 이 테이블은 개인정보 저장소다

여기에 담기는 내용을 생각해 봅시다.

- “전화번호 010-1234-5678로 주문했는데 …”
- “서울시 강남구 테헤란로 142 306호 맞아요”
- “주문 건 카드 뒷자리 1234…”

고객이 **자발적으로** 제공한 정보일 뿐, 적절한 관리가 없으면 법적 리스크입니다.

| 리스크 | 대응 |
| --- | --- |
| 평문 저장 | 저장 전 민감 정보 마스킹 (Round 5 Guardrail) |
| 무기한 보존 | 주기적 배치로 오래된 세션 삭제 (예: 90일 룰) |
| 접근 제어 | DB 계정 분리 — 상담원 조회는 read-only 뷰만 |
| 암호화 | 컬럼 암호화 또는 DB-level TDE |

> 🎯
**핵심 메시지**: Memory를 영속화하는 순간, 당신은 **개인정보 처리자**가 된다. 저장 기술을 바꾸는 결정은 법무/보안팀과 함께 해야 한다.
>

### 4.5 Memory와 Tool Calling의 상호작용

Round 2에서 배운 Tool 호출 결과도 Memory에 남을까요?

- `MessageChatMemoryAdvisor`의 기본 동작(Spring AI 1.0 GA 기준): **USER / ASSISTANT 메시지만 저장**. ToolMessage(Tool 요청·응답)는 Memory에 적재되지 **않는다**.
- 즉, 2회차에서 LLM이 “그거”를 해석하려면 **1회차의 ASSISTANT 응답 본문에 orderId가 포함돼 있어야** 한다.
- Tool 응답을 그대로 Memory에 남기는 건 토큰 관점에서 위험하다 (JSON 전체가 메모리에 쌓임) — Spring AI 1.0이 USER/ASSISTANT만 저장하는 이유이기도 하다.

> 💡
시스템 프롬프트의 `[대화 맥락 사용 규칙]`에 “이전 턴에서 이미 Tool로 조회한 정보는 재사용하라”고 명시한 이유가 이것입니다. LLM이 자연어 응답 안에 orderId를 포함시키면, 다음 턴에서 그걸 다시 읽을 수 있습니다.
>

### 4.6 Memory 오염 시나리오 — 크기 무제한의 결과

크기 제한 없이 대화를 쌓으면 무슨 일이 생기는지 직접 보여줍니다.

```java
// MAX_MESSAGES = 20 대신 Integer.MAX_VALUE로 임시 변경 후
```

30턴짜리 상담을 연달아 돌리면 콘솔의 입력 토큰 수가 다음처럼 변합니다.

| 턴 | 입력 토큰 (대략) |
| --- | --- |
| 1 | 200 |
| 10 | 1,800 |
| 20 | 3,500 |
| 30 | 5,400 |

선형 증가입니다. 100턴이면 18,000 토큰, 200턴이면 컨텍스트 윈도우를 넘깁니다.

> 💡
이 실험은 숙제 2단계에서 직접 해 봅니다. “입력 토큰을 선형으로 증가시키는 건 곧 비용이 선형 증가한다는 뜻”을 체감하는 데 목적이 있습니다.
>

---

## 다음 라운드 예고 — Round 4: RAG로 배달 정책/FAQ 지식 연동

다음 시간에는 **“알지 못하는 것을 답하게 하는 방법”** 을 다룹니다.

- 고객: “비 오는 날 배달이 늦으면 보상 받을 수 있나요?”
- 이 질문은 Tool로도, Memory로도 답할 수 없습니다. **배달의 정책 문서**가 있어야 답할 수 있죠.
- `PgVector` + 문서 임베딩 + `QuestionAnswerAdvisor`로 RAG 파이프라인을 만듭니다.
- Round 3의 Memory Advisor 옆에 Q&A Advisor가 나란히 붙습니다. **Advisor 체인의 진가**가 드러납니다.
- (선행 학습 권장)
    - Docker로 `pgvector/pgvector` 이미지 띄워보기
    - Ollama에서 `ollama pull nomic-embed-text`로 임베딩 모델 사전 다운로드
    - Spring AI의 `VectorStore`, `Document`, `EmbeddingModel` 문서 한 번 훑기

대용량 처리할 때는 S3Vector / 소규모 처리할 때는 pgVector