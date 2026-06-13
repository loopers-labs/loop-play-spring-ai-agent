# 4단계: Observability + AI 코드 리뷰

## 토큰 비용 관찰

```
질문: "배달 완료 후에도 환불 받을 수 있나요?"
```

| 조건 | Advisor 체인 | 입력 토큰 | 출력 토큰 | 응답 시간(ms) | 비고 |
|---|---|---|---|---|---|
| (a) Memory 없음 + RAG 없음 | performanceAdvisor만 | 1404 | 81 | 107,333 | Fallback 출력 |
| (b) Memory만 | memoryAdvisor, performanceAdvisor | 1404 | 81 | 18,045 | Memory 비어 (a)와 동일 |
| (c) Memory + RAG | memoryAdvisor, ragAdvisor, performanceAdvisor | 2310 | 90 | 198,377 | RAG Context 블록 포함 |

**(c) - (a) 토큰 차이: +906.** 이 906 토큰이 RAG가 주입한 `refund-after-delivered` + `refund-basic` 문서 원문이다.

**응답 비교**

조건 (a), (b) 응답 (RAG 없음):
```
죄송합니다. 해당 문의에 대한 정확한 정책 정보를 찾지 못했습니다. 고객센터(1588-0000)로 연락해 주시면 더 자세히 안내해 드리겠습니다.

만약 배달 완료 후 환불을 원하신다면, 언제든지 고객센터에 문의해 주세요.
```

조건 (c) 응답 (RAG 있음):
```
배달 완료 후에도 특정 사유에 따라 환불이 가능합니다. 주문한 메뉴 중 일부가 누락되었거나 품질 문제가 있는 경우 환불을 신청할 수 있습니다.

어떤 사유로 환불을 원하시는지 알려주시겠어요? 예를 들어, 메뉴 누락이나 품질 불량 등이 있으면 말씀해 주세요.
```

> 동일한 질문인데 Advisor 체인 구성에 따라 응답이 완전히 달라진다. (a)/(b)는 정책 문서 없이 Fallback만 출력했고, (c)는 "메뉴 누락", "품질 문제" 같은 정책 원문 조건을 포함한 정확한 답변을 했다. RAG 하나가 응답 품질의 결정 변수였다.
>
> **(b)가 (a)와 입력 토큰·출력 토큰이 동일한 이유**: 첫 질문이라 Memory가 비어 있다. `MessageChatMemoryAdvisor`가 실행됐지만 주입할 이전 대화가 없어 프롬프트에 아무것도 추가하지 않았다 → (a)와 동일한 프롬프트로 동일한 LLM 호출이 일어났다. Memory Advisor는 세션이 쌓인 뒤에야 토큰 비용에 영향을 준다.

---

## Context 블록 원문 캡처

조건 (c) 실행 결과: `입력 토큰: 2310 | 출력 토큰: 82`

응답:
```
배달 완료 후에도 특정 사유에 따라 환불이 가능합니다. 주문한 메뉴가 누락되었거나 품질 문제가 있는 경우 환불을 요청할 수 있습니다. 하지만 단순 맛 불만족은 환불 대상에서 제외됩니다.

어떤 사유로 환불을 원하시는지 알려주시겠어요?
```

Spring AI 1.0의 `QuestionAnswerAdvisor`는 검색된 문서를 사용자 메시지 뒤에 `Context:` 형태로 주입하지만 DEBUG 로그에 Context 원문을 별도로 출력하지는 않았다. 토큰 차이 2310 - 1404 = **+906 토큰**이 RAG가 주입한 Context의 크기다.

응답에 등장하는 원문 수치를 역추적하면 주입된 문서를 특정할 수 있다.

**`refund-after-delivered.md`에서 주입된 내용**:
```
배달이 완료된 상태에서도 아래 사유에 한해 환불을 요청할 수 있습니다.

1. 메뉴 누락: 주문한 메뉴 중 일부가 도착하지 않은 경우.
2. 오배송: 주문한 메뉴가 아닌 다른 메뉴가 도착한 경우.
3. 품질 불량: 음식에 이물질이 포함되었거나, 상한 음식이 도착한 경우.
4. 수량 오류: 주문 수량보다 적게 도착한 경우.

접수 시한: 배달 완료 후 24시간 이내 접수만 유효합니다.
24시간을 초과하면 단순 맛 불만족과 함께 환불 대상에서 제외됩니다.
```

**`refund-basic.md`에서 주입된 내용**:
```
조리 시작 전 취소: 주문 상태가 CREATED 또는 ACCEPTED인 경우, 고객 요청으로 전액 즉시 취소/환불 가능합니다.
음식 누락 / 오배송: 배달 완료 후에도 사진 등 증빙을 확인하는 조건으로 전액 또는 해당 메뉴 부분 환불이 가능합니다.
배달 완료 후 24시간 초과: 배달 완료 후 24시간이 지난 단순 맛 불만족은 환불 대상이 아닙니다.
```

응답의 "메뉴 누락", "품질 문제", "단순 맛 불만족은 환불 대상에서 제외"가 모두 위 두 문서의 원문에서 직접 왔다. 906 토큰 차이가 단순한 숫자가 아니라 응답 품질 차이를 만든 실제 정책 텍스트라는 것을 알 수 있다.

---

## AI 코드 리뷰 — 프로덕션 결함 찾기

### AI 생성 코드 원본

**프롬프트**: `"Spring AI 1.0으로 RAG 기반 FAQ 챗봇을 만들어줘. PgVector와 OpenAI 임베딩을 써."`

```java
// FaqChatService.java
@Service
public class FaqChatService {
    private static final String SYSTEM_PROMPT = """
            You are a Korean FAQ chatbot.
            Answer only from the retrieved FAQ context.
            If the context does not contain the answer, say "FAQ에서 관련 정보를 찾을 수 없습니다."
            Keep answers concise and practical.
            """;
    private final ChatClient chatClient;

    public FaqChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        var advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(4)
                        .similarityThreshold(0.65)
                        .build())
                .build();
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(advisor)
                .build();
    }

    public String ask(String question) {
        return this.chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}

// FaqDataLoader.java
@Component
public class FaqDataLoader implements ApplicationRunner {
    private final VectorStore vectorStore;
    private final FaqProperties faqProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!this.faqProperties.loadSampleData() || this.faqProperties.items().isEmpty()) {
            return;
        }
        List<Document> documents = this.faqProperties.items().stream()
                .map(item -> Document.builder()
                        .text("Q: " + item.question() + "\nA: " + item.answer())
                        .metadata(Map.of("source", "application.yaml", "question", item.question()))
                        .build())
                .toList();
        this.vectorStore.add(documents);
        log.info("Loaded {} FAQ documents into PgVector", documents.size());
    }
}
```

```yaml
# application.yml
spring:
  ai:
    openai:
      embedding:
        options:
          model: text-embedding-3-small
    vectorstore:
      pgvector:
        initialize-schema: true
        dimensions: 1536
faq:
  load-sample-data: true
```

---

### 결함 분석

#### 결함 1 — 중복 적재 방지 없음 (Critical)

**문제 코드** (`FaqDataLoader.java`):
```java
this.vectorStore.add(documents); // 재기동마다 무조건 실행
```

`loadSampleData: true`이면 앱이 재시작될 때마다 동일한 FAQ 문서가 중복으로 쌓인다. 10번 재기동하면 같은 문서가 10배로 늘어나고, Top-K=4로 검색해도 동일 내용의 중복 청크 4개만 가져온다. 답변 내용은 같아 보이지만 관련 없는 정책 문서가 순위에서 밀려나고, 잠재적 품질 저하가 재기동 횟수에 비례해 누적된다.

**개선 방안**:
```java
private boolean alreadyLoaded(String questionKey) {
    SearchRequest req = SearchRequest.builder()
            .query("확인")
            .topK(1)
            .similarityThresholdAll()
            .filterExpression("question == '" + questionKey + "'")
            .build();
    return !vectorStore.similaritySearch(req).isEmpty();
}
```
`alreadyLoaded()` 체크를 추가해 이미 적재된 문서는 스킵. 이번 수업의 `KnowledgeLoader.alreadyLoaded()` 패턴과 동일하다.

---

#### 결함 2 — TokenTextSplitter 미사용 (청크 무분할)

**문제 코드** (`FaqDataLoader.java`):
```java
.text("Q: " + item.question() + "\nA: " + item.answer())
// 청크 분할 없이 통째로 add
```

FAQ 답변이 길어질수록 여러 주제가 한 벡터에 뭉쳐 유사도가 뭉툭해진다. "배송 기간" 질문을 임베딩하면 배송+환불+영업시간이 섞인 청크가 Top-K에 올라와 관련 없는 내용이 Context를 오염시킨다. 이번 실험 B(chunkSize=100)에서는 "너무 잘게 쪼갠" 반대 문제를 관찰했다. 분할 자체가 없으면 그 반대 극단의 문제가 생긴다. 적절한 청크 크기 선택이 RAG 품질의 기반이다.

**개선 방안**:
```java
// TokenTextSplitter를 Bean으로 등록하고 적재 시 apply()
List<Document> chunks = tokenTextSplitter.apply(documents);
vectorStore.add(chunks);
```
`TokenTextSplitter(800, 350, 5, 10_000, true)`로 청크 분할. FAQ 답변이 짧으면 1개 청크로 유지되고, 길어지면 조항 단위로 분리된다.

---

#### 결함 3 — `initialize-schema: true`를 프로덕션 설정에 남겨둠

**문제 코드** (`application.yml`):
```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        initialize-schema: true  # 프로덕션 설정에 있으면 위험
```

`application.yml`은 프로덕션을 포함한 모든 환경에서 로드된다. 운영 환경에서 앱이 시작될 때마다 스키마 변경을 시도해 예상치 못한 DDL이 실행된다. 기존 인덱스나 제약 조건과 충돌하면 기동 실패로 이어지고, 충돌이 없어도 매 배포마다 DDL이 실행된다는 사실 자체가 운영 위험이다.

**개선 방안**:
```yaml
# application.yml (프로덕션)
spring.ai.vectorstore.pgvector.initialize-schema: false

# application-local.yml 또는 application-dev.yml
spring.ai.vectorstore.pgvector.initialize-schema: true
```
또는 Flyway/Liquibase로 스키마 마이그레이션을 분리해 버전 관리한다. 이번 수업에서는 로컬 개발용이라 `true`를 사용했지만 프로덕션에서는 반드시 분리해야 한다.

---

#### 결함 4 — Memory Advisor 없음 (다턴 대화 불가)

**문제 코드** (`FaqChatService.java`):
```java
this.chatClient = chatClientBuilder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultAdvisors(advisor)  // QuestionAnswerAdvisor만 있음
        .build();
```

`MessageChatMemoryAdvisor`가 없어서 다턴 대화 맥락이 유지되지 않는다. "아까 그 환불 건"처럼 이전 질문을 참조하는 후속 질문이 불가능하다. 이번 실험 2(rag(5) → memory(10) 고장 케이스)에서 관찰한 것처럼, Memory 없이는 "그 주문"이 무엇인지 모르는 채로 RAG가 검색을 실행해 잘못된 정책 문서가 Context에 들어온다.

**개선 방안**:
```java
// MessageChatMemoryAdvisor를 Memory(10) → RAG(20) 순서로 추가
this.chatClient = chatClientBuilder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultAdvisors(memoryAdvisor, ragAdvisor)  // 순서 중요
        .build();
```
Memory가 먼저 이전 맥락을 복원하고, RAG가 그 복원된 질문으로 검색해야 한다. 순서를 바꾸면 3단계 실험 2에서 관찰한 것처럼 RAG가 잘못된 정책 문서를 가져와 LLM이 주문 상태를 오판한다.

---

### 결함 요약

| # | 결함 | 심각도 | 이번 수업에서 배운 해결책 |
|---|---|---|---|
| 1 | 중복 적재 방지 없음 | Critical | `alreadyLoaded()` + `filterExpression` |
| 2 | TokenTextSplitter 미사용 | High | `TokenTextSplitter(800, 350, ...)` |
| 3 | initialize-schema=true 프로덕션 노출 | High | 프로파일 분리 또는 Flyway |
| 4 | Memory Advisor 없음 | Medium | `MessageChatMemoryAdvisor` order=10 → QA order=20 |

---

## 자가 점검 체크리스트

- [x] (a)/(b)/(c) 3조건 입력 토큰 비교 표
- [x] (c)에서 주입된 Context 원문 문서 확인 (토큰 차이 +906 근거)
- [x] AI 생성 코드 원본 첨부
- [x] 결함 4개 + 각각의 개선 방안 (수업 내용과 연결)
