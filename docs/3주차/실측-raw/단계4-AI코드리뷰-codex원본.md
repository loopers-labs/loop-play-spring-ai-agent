# 단계 4 AI 코드 리뷰 raw

요청:

```text
Spring AI 1.0으로 배달 챗봇에 대화 메모리 기능을 붙여줘. 세션별로 대화가 유지되어야 해.
짧고 실용적인 예시 코드를 보여줘.
```

생성 코드:

```java
@Configuration
class ChatMemoryConfig {

    @Bean
    ChatMemory chatMemory() {
        return new InMemoryChatMemory();
    }
}

@RestController
@RequestMapping("/api/chat")
class DeliveryChatController {

    private final ChatClient.Builder builder;
    private final ChatMemory chatMemory;

    DeliveryChatController(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.builder = builder;
        this.chatMemory = chatMemory;
    }

    @PostMapping
    String chat(@RequestBody ChatRequest request) {
        ChatClient chatClient = builder
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build();

        return chatClient.prompt()
                .system("너는 배달 주문 상담 챗봇이다.")
                .user(request.message())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, request.sessionId()))
                .call()
                .content();
    }

    record ChatRequest(String sessionId, String message) {
    }
}
```

## 바로 보이는 결함

1. `ChatClient.Builder`로 요청마다 `build()`를 다시 호출한다. 지금 프로젝트에서는 `defaultTools(...)`와 `defaultAdvisors(...)`를 생성자에서 한 번만 묶고, 요청별로는 conversation id만 넘긴다.
2. `InMemoryChatMemory`라는 타입은 현재 Spring AI 1.0 구현 방향과 맞지 않는다. 이번 프로젝트는 `ChatMemoryRepository` 위에 `MessageWindowChatMemory`를 얹고, 그 위에 `MessageChatMemoryAdvisor`를 붙였다.
3. Memory window 제한이 없다. 긴 상담에서는 토큰 비용과 불필요한 개인정보 보관 범위가 같이 커진다.
4. `sessionId`가 비어 있거나 빠졌을 때의 정책이 없다. 현재 구현은 헤더가 없으면 `default`로 모으되, 운영에서는 사용자/브라우저/상담 세션 정책을 더 엄격히 봐야 한다.
5. JDBC 같은 영속 저장소 전환 기준이 없다. 서버 재시작, 멀티 인스턴스, 상담 이력 보존이 필요하면 InMemory만으로는 부족하다.

이 raw는 모델 비교 실험이 아니다. 이 프로젝트의 현재 구현 방향과 비교하기 위해 한 번 생성한 코드 예시다.
