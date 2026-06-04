# 4단계: Observability + AI 코드 리뷰

## 10턴 토큰 증가 관찰 — PerformanceLoggingAdvisor

### 턴별 상세 (MAX_MESSAGES = 20, s4-obs 세션)

| 턴 | 질문 요약 | 입력 토큰 | 출력 토큰 | 응답 시간(ms) | Tool |
|---|---|---|---|---|---|
| 1 | 1234 배달 상황 | 1290 | 147 | 134083 | getDeliveryStatus(1234) |
| 2 | 그거 몇 분 | 1591 | 206 | 168349 | getDeliveryStatus(1234) |
| 3 | 1235 메뉴 | 917 | 49 | 37545 | 없음 |
| 4 | 아 그 버거 세트 | 2130 | 172 | 65305 | getOrderDetail(1235) |
| 5 | 1234 취소 가능해요? | 2501 | 152 | 95320 | getDeliveryStatus(1234) + cancelOrder(1234) |
| 6 | 1235 취소되죠? | 1257 | 112 | 43075 | 없음 |
| 7 | 그거 취소 (1235) | 2849 | 69 | 30342 | cancelOrder(1235) |
| 8 | 아까 1234 도착 | 1440 | 98 | 34504 | 없음 |
| 9 | 그 주문 라이더 위치 | 3235 | 141 | 59760 | getDeliveryStatus(1234) |
| 10 | 요약 | 1670 | 247 | 78500 | 없음 |

> 응답 시간은 Windows Ollama 기준이다. (1회차는 Mac 사용)

### 1턴 대비 10턴 — 배수 계산

- T1 입력 토큰: **1290**
- T10 입력 토큰: **1670**
- T1 → T10 배수: **1.29x**

숫자만 보면 별로 늘지 않은 것처럼 보이는데, T1은 getDeliveryStatus Tool 결과 JSON이 포함됐고, T10은 Tool 없이 Memory 텍스트만 들어갔다. Tool JSON이 빠진 자리를 쌓인 대화 이력이 메웠다. 같은 이유로 두 수치가 비슷하게 보인다.

실질적인 증가를 보려면 같은 조건끼리 비교해야 한다. T1(1290) 대비 T9(3235)를 보면, 둘 다 getDeliveryStatus를 호출한 턴이다.

- T1 vs T9: **3235 / 1290 = 2.51x**

8턴 동안 Memory가 쌓이면서 Tool이 있는 턴의 입력 토큰이 2.5배로 늘었다.

### Memory 기여분만 추적 — Tool 없는 턴

Tool JSON 영향을 제거하고 Memory 누적만 보려면 Tool 미호출 턴만 추려야 한다.

| 턴 | 입력 토큰 | 직전까지 Memory 메시지 수 | 비고 |
|---|---|---|---|
| 3 | 917 | 4개 (T1·T2 각 USER+ASSISTANT) | Tool 없음 |
| 6 | 1257 | 10개 (T1~T5) | Tool 없음 |
| 8 | 1440 | 14개 (T1~T7) | Tool 없음 |
| 10 | 1670 | 18개 (T1~T9) | Tool 없음 |

T3(917) → T10(1670): **1.82x**. 순수하게 Memory가 쌓인 결과다.  
T3에서 T10까지 14개 메시지가 추가됐고, 토큰은 약 753 늘었다. 메시지당 약 54토큰 꼴이다.  
이 속도라면 MAX_MESSAGES = 20 윈도우가 찰 때(약 21턴 이후)부터 Memory 기여분은 수렴하고, 그 이후 입력 토큰은 Tool 호출 여부에 따라 오르내린다.

---

## Memory가 프롬프트에 끼어드는 모습

T7 "그거 취소해주세요"가 "그거" = 1235로 올바르게 해석된 것은 Memory 덕분이다.  
T6에서 이미 "1235는 취소 가능하다"는 내용이 ASSISTANT 응답으로 Memory에 들어갔고, T7 프롬프트에는 T1~T6의 대화 전체가 앞에 붙어 있었다.

**T6 프롬프트 구조** (1257 토큰, Tool 없음):
```
[SYSTEM]  ASSISTANT_PROMPT
[TOOLS]   getOrderDetail / getDeliveryStatus / cancelOrder 스키마
[T1-USER] "2024-1234 배달 상황 알려주세요"
[T1-AST]  "라이더가 배달 중이며, 역삼역 사거리 부근..."
[T2-USER] "그거 몇 분 남았어요?"
[T2-AST]  "현재 2024-1234 주문은 라이더가 배달 중..."
[T3-USER] "2024-1235 주문도 있는데 메뉴 뭐였죠?"
[T3-AST]  "어떤 주문을 말씀하시는 건가요?..."
[T4-USER] "아 그 버거 세트"
[T4-AST]  "2024-1235번 주문의 메뉴는 와퍼 세트..."
[T5-USER] "2024-1234 취소 가능해요?"
[T5-AST]  "취소할 수 없습니다..."
[T6-USER] "그럼 1235는 취소되죠?"    ← 현재 입력
```

**T7 프롬프트 구조** (2849 토큰, cancelOrder 호출):
```
[SYSTEM]  ASSISTANT_PROMPT
[TOOLS]   스키마 (동일)
[T1~T5]   위와 동일한 10개 메시지
[T6-USER] "그럼 1235는 취소되죠?"    ← Memory에서 추가됨
[T6-AST]  "취소할 수 있습니다..."     ← Memory에서 추가됨
[T7-USER] "그거 취소해주세요"         ← 현재 입력
```

T7에서 "그거" = 1235라는 판단은 T6-ASSISTANT("1235는 취소 가능합니다")가 Memory에 있었기 때문이다. `MessageChatMemoryAdvisor`가 LLM 호출 직전 이 12개 메시지를 프롬프트 앞에 조립해 넣었다. LLM 자체에는 이전 대화를 기억하는 능력이 없고, Memory가 그 역할을 한다.

T6(1257) → T7(2849) 차이(+1592): T6-USER+T6-ASSISTANT 추가분(~180토큰) + cancelOrder Tool JSON(~1400토큰)

---

## 이번 실행에서 관찰된 특이 사항

### T1 — 중국어 혼입 응답

```
현재您的消息似乎被误输入了，请重新输入一个关于2024-1234订单的问题...
如果您想了解2024-1234订单的配送情况，我可以告诉您：라이더가 배달 중이며...
```

한국어 질문에 중국어가 섞여 나왔다. qwen2.5의 언어 전환 불안정이다. 2단계에서도 같은 패턴이 나왔다. 응답 자체의 정보(배달 위치, 시간)는 맞지만, 중국어가 섞인 ASSISTANT 응답이 Memory에 그대로 들어가 이후 대화에서 오염된 맥락으로 쓰인다.

### T2 — 재사용 지시를 무시하고 Tool 재호출

"그거 몇 분 남았어요?"에서 `getDeliveryStatus(1234)`를 다시 호출했다. ASSISTANT_PROMPT에 "이미 조회한 정보는 재사용하라"는 지시가 있는데도 1회차 결과를 쓰지 않았다. 1단계 시나리오 1에서 반대로 재호출 없이 Memory 재사용만 한 것과 정반대다. 같은 프롬프트, 같은 모델에서 비결정적 동작이 나온 것이다. qwen2.5에서 프롬프트 지시 준수는 확정적이지 않다.

### T5 — "취소 가능해요?" 질문에 실제 취소 시도

```
[Tool] getDeliveryStatus(orderId=2024-1234)
[Tool] cancelOrder(orderId=2024-1234, reason=고객 요청)
```

"2024-1234 취소 가능해요?"는 가능 여부를 묻는 질문이다. 그런데 모델이 getDeliveryStatus로 상태를 확인하고 곧바로 cancelOrder까지 호출했다. cancelOrder가 NOT_CANCELABLE을 반환해서 실제 취소는 이루어지지 않았고 응답도 "취소할 수 없습니다"로 나왔지만, 모델이 승인받지 않은 작업을 시도했다는 점은 위험하다. 취소가 가능한 주문이었다면 고객이 요청하지도 않은 취소가 실행됐을 것이다.

배달 상담처럼 금전 피해가 직결되는 도메인에서는 "~할 수 있나요?" 형태의 질문이 실행 명령으로 해석되는 패턴을 프로덕션 전에 반드시 확인해야 한다.

### T10 — 요약 전체 중국어

```
현재까지의 대화 요약如下：
1. 您询问了2024-1234订单的配送状态...
```

10턴의 대화를 전부 중국어로 요약했다. T1의 중국어 혼입 응답이 Memory에 남아 있다가 T10 프롬프트에 함께 들어간 것이 영향을 줬을 가능성이 있다. Memory에 잘못된 언어의 메시지가 한 번 쌓이면 이후 응답 품질도 연쇄적으로 흔들릴 수 있다는 것을 보여준다.

---

## AI 코드 리뷰 — 프로덕션 결함 찾기

### 프롬프트

```
Spring AI 1.0으로 배달 챗봇에 대화 메모리 기능을 붙여줘. 세션별로 대화가 유지되어야 해.
```

> AI 도구: Codex

### AI 생성 원본 코드

```java
// DeliveryChatController.java
@RestController
@RequestMapping("/api/delivery-chat")
class DeliveryChatController {

    private final DeliveryChatService deliveryChatService;

    DeliveryChatController(DeliveryChatService deliveryChatService) {
        this.deliveryChatService = deliveryChatService;
    }

    @PostMapping
    DeliveryChatResponse chat(
            @RequestHeader(name = "X-Session-Id", required = false) String headerSessionId,
            @RequestBody DeliveryChatRequest request) {

        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        String sessionId = resolveSessionId(headerSessionId, request.sessionId());
        return deliveryChatService.chat(sessionId, request.message());
    }

    private String resolveSessionId(String headerSessionId, String requestSessionId) {
        if (StringUtils.hasText(headerSessionId)) {
            return headerSessionId;
        }
        if (StringUtils.hasText(requestSessionId)) {
            return requestSessionId;                  // (2)
        }
        return UUID.randomUUID().toString();           // (1)
    }
}

// DeliveryChatService.java
@Service
class DeliveryChatService {

    private final ChatClient chatClient;

    DeliveryChatService(ChatClient deliveryChatClient) {
        this.chatClient = deliveryChatClient;
    }

    DeliveryChatResponse chat(String sessionId, String message) {
        String content = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        return new DeliveryChatResponse(sessionId, content);
    }
}

// DeliveryChatConfig.java
@Configuration
class DeliveryChatConfig {

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();                              // (3)
    }

    @Bean
    ChatClient deliveryChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem("""
                        You are a delivery order chatbot.
                        Help users choose menus, update delivery details, and remember prior turns in the same session.
                        Ask concise follow-up questions when required information is missing.
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
```

### 프로덕션 결함 3개

#### 결함 1 — UUID 자동 생성 폴백 → 미아 세션 무한 적재 (메모리 누수)

**(1)** 헤더도 없고 body sessionId도 없으면 `UUID.randomUUID()`를 생성해 반환한다. 이 UUID가 `DeliveryChatResponse`에 담겨 클라이언트로 돌아가지만, 클라이언트가 이 값을 저장해서 다음 요청에 쓰지 않으면 매 요청마다 새 세션이 생겨 대화가 이어지지 않는다. 모바일 앱에서 헤더를 별도로 관리하지 않으면 티 안 나게 발생한다.

더 심각한 건 메모리 누수다. 단 1턴만 쓰고 다시는 쓰이지 않는 UUID 세션이 Memory의 ConcurrentHashMap에 계속 쌓인다. TTL이 없으니 JVM이 종료될 때까지 정리되지 않는다. 이번 10턴 실험에서 빈 세션 기준 입력 토큰이 ~570이었는데, 미아 세션이 수만 개 쌓이면 힙 압박으로 OOM이 발생할 수 있다.

**개선 1. 세션 식별 방식**

`resolveSessionId`에서 UUID 폴백을 제거하고 헤더 없는 요청은 400으로 거부한다. 세션 ID는 서버가 발급하거나 클라이언트가 로그인 시 받은 JWT에서 추출한다. 1단계 설계 결정 문서의 "서버 발급 UUID" 방어 수단이다.

```java
// 수정 전
return UUID.randomUUID().toString();  // 매 요청 새 세션, 누수

// 수정 후 — 헤더 없으면 400, 클라이언트가 세션 ID를 직접 생성하지 않음
@PostMapping("/session")
SessionResponse createSession() {
    return new SessionResponse(UUID.randomUUID().toString());  // 서버가 발급
}

// DeliveryChatController에서 UUID 폴백 완전 제거
@RequestHeader(name = "X-Session-Id") String sessionId  // required=true
```

**개선 2. TTL**

미아 세션 자동 정리를 위해 `@Scheduled`로 주기적으로 오래된 InMemory 세션을 삭제하거나, 3단계에서 배운 JDBC 전환 후 `SPRING_AI_CHAT_MEMORY` 테이블에 90일 TTL 배치를 추가한다.

```java
// JDBC 전환 후 — 90일 지난 행 삭제 배치 (3단계 비기능 요구사항)
@Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시
public void purgeExpiredSessions() {
    jdbcTemplate.update(
        "DELETE FROM SPRING_AI_CHAT_MEMORY WHERE timestamp < ?",
        LocalDateTime.now().minusDays(90)
    );
}
```

---

#### 결함 2 — Body `sessionId`를 클라이언트가 임의로 제어 가능 → 세션 탈취

**(2)** 헤더가 없을 때 Body의 `sessionId`를 신뢰한다. 공격자가 다른 고객의 세션 ID를 Body에 넣으면 그 세션의 대화 이력에 접근하거나 오염시킬 수 있다. 이번 실험 T5에서 본 것처럼 모델이 "취소 가능해요?"를 실행 명령으로 해석하는 패턴까지 겹치면, 오염된 세션이 주입됐을 때 다른 고객의 주문이 잘못 취소될 수 있다.

1단계 설계 결정 문서에서 분석한 "세션 고정 공격" 패턴이다. UUID 형식이 예측 불가능해도, 공격자가 세션 ID를 이미 알고 있다면(로그 유출, 공유 단말 등) Body를 통해 주입할 수 있다.

**개선 1. 세션 식별 보안 방식**

Body sessionId 수락 경로를 완전히 제거한다. 세션 ID 결정권을 클라이언트에서 서버로 이전한다.

```java
// 수정 전 — Body sessionId도 신뢰
if (StringUtils.hasText(requestSessionId)) return requestSessionId;

// 수정 후 — 헤더만 허용, Body 경로 없음
// 옵션 A: 서버 발급 UUID를 X-Session-Id 헤더로만 받음
@RequestHeader(name = "X-Session-Id") String sessionId

// 옵션 B: 로그인 사용자라면 JWT 클레임에서 세션 키 추출
// → 1단계 세션 식별 비교표에서 "배달 앱에 가장 적합"으로 평가한 방식
String sessionId = jwtClaims.getSubject();  // 사용자 ID를 세션 키로 사용
```

소유권 바인딩까지 추가하면 더 안전하다. 세션 ID가 맞아도 인증된 사용자 ID와 불일치하면 403을 반환한다.

---

#### 결함 3 — InMemory 단일 인스턴스 제약 — 재시작·멀티 인스턴스 미고려

**(3)** `MessageWindowChatMemory.builder().maxMessages(20).build()`는 내부적으로 `InMemoryChatMemoryRepository`(ConcurrentHashMap)를 쓴다. Thread-safe하지만 JVM 힙에만 존재한다.

재시작하면 모든 세션 데이터가 사라진다. 3단계 재시작 실험 1에서 직접 확인했다. `jdbc:h2:mem`도 JVM이 꺼지면 InMemory와 똑같이 날아간다. 로드밸런서 뒤 멀티 인스턴스에서는 인스턴스 A에 쌓인 세션이 인스턴스 B에 없다. 3단계 의사결정 트리에서 JDBC가 필요한 조건 1("멀티 인스턴스")과 조건 2("재시작 후 연속성")가 모두 해당한다.

저장소를 `builder()`에 암묵적으로 생성하기 때문에, 나중에 JDBC로 전환하려면 `DeliveryChatConfig`를 열어 직접 수정해야 한다. 배포 환경마다 코드를 바꾸는 구조다.

**개선 1. 저장소 전환 구조**

저장소를 명시적 Bean으로 분리하고 `@Profile("!jdbc")`를 달면, `application-jdbc.yml` 활성화만으로 `JdbcChatMemoryRepository`로 교체된다. 코드 수정 없이 환경 변수 하나로 전환 가능하다.

```java
// 수정 전 — 저장소가 내부에 숨겨져 있어 전환 불가
ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder().maxMessages(20).build();
}

// 수정 후 — @Profile로 환경별 전환 경로 확보 (3단계 구조 그대로)
@Bean
@Profile("!jdbc")
ChatMemoryRepository chatMemoryRepository() {
    return new InMemoryChatMemoryRepository();   // 개발/단일 인스턴스
}

// jdbc 프로필 활성화 시 → spring-ai-starter-model-chat-memory-repository-jdbc가
// JdbcChatMemoryRepository를 자동 등록 (application-jdbc.yml만 추가)

@Bean
ChatMemory chatMemory(ChatMemoryRepository repo) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repo)
            .maxMessages(20)
            .build();
}
```

### 개선 코드 전문

```java
// DeliveryChatController.java — Body sessionId 제거, UUID 폴백 제거
@RestController
@RequestMapping("/api/delivery-chat")
class DeliveryChatController {

    private final DeliveryChatService deliveryChatService;

    DeliveryChatController(DeliveryChatService deliveryChatService) {
        this.deliveryChatService = deliveryChatService;
    }

    @PostMapping
    DeliveryChatResponse chat(
            @RequestHeader(name = "X-Session-Id") String sessionId,  // required=true, 없으면 400
            @RequestBody DeliveryChatRequest request) {

        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        return deliveryChatService.chat(sessionId, request.message());
    }
}

// DeliveryChatService.java — 변경 없음 
@Service
class DeliveryChatService {

    private final ChatClient chatClient;

    DeliveryChatService(ChatClient deliveryChatClient) {
        this.chatClient = deliveryChatClient;
    }

    DeliveryChatResponse chat(String sessionId, String message) {
        String content = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        return new DeliveryChatResponse(sessionId, content);
    }
}

// DeliveryChatConfig.java — 저장소 명시 주입 + @Profile("!jdbc")
@Configuration
class DeliveryChatConfig {

    @Bean
    @Profile("!jdbc")
    ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repo) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(20)
                .build();
    }

    @Bean
    ChatClient deliveryChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem("""
                        당신은 배달 상담 전문 AI입니다.
                        메뉴 선택, 배달 현황 확인, 주문 취소 등을 도와주세요.
                        같은 세션 내 이전 대화를 기억하고 활용하세요.
                        도구 응답에 없는 정보는 추측하지 마세요.
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
```

원본 코드 대비 바뀐 것:
- `resolveSessionId` 제거 → 헤더 없으면 Spring이 400 반환, Body sessionId 경로 없앰
- `chatMemoryRepository()` Bean을 `@Profile("!jdbc")`로 분리 → `application-jdbc.yml`로 전환 가능
- 저장소를 `chatMemory()`에 명시적으로 주입 → 전환 경로 가시성 확보
- 시스템 프롬프트를 한국어로 변경 + 할루시네이션 방지 지시 추가

---

## 자가 점검 체크리스트

- [x] 10턴 입력 토큰 증가 표 + 1턴 대비 배수 계산 (T1:1290 → T10:1670 = 1.29x, Tool 동조건 T1 vs T9 = 2.51x)
- [x] Memory가 프롬프트에 끼어드는 구조 증명 (T6→T7 프롬프트 재구성 + 토큰 차이 분석)
- [x] AI 생성 코드 원본 첨부 (Codex 생성)
- [x] 결함 3개 + 각각의 개선 방안 (1단계 세션 탈취, 2단계 미아 세션 누수, 3단계 JDBC 전환 경로)
