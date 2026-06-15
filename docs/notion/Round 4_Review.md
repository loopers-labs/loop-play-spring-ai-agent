# Round 4 Code Review Guide

# 4주차 코드 리뷰 가이드 — RAG로 정책/FAQ 지식 연동

> 대상: 동료 수강생끼리 미션 코드를 서로 리뷰하는 상황
목적: “동작한다”를 넘어 **검색 품질 경계(청크 / Top-K / 임계값 / Fallback) 설계 근거**까지 함께 짚어 보는 리뷰
>

이 가이드는 리뷰어(동료)가 보는 체크리스트 + 흔한 실수 + 모범 답안 + 리뷰 코멘트 예시로 구성된다.
채점자가 아닌 **동료의 시선**으로 “내가 짠 코드였다면 어떻게 고쳤을까”를 함께 논의하는 데 쓴다.

---

## 리뷰 체크리스트

### A. 필수 확인 — “일단 돌아가는가”

- [ ]  `docker compose up -d`로 `baedal-pgvector` 컨테이너가 healthy 상태인가
- [ ]  `ollama list`에 `qwen2.5` + `qwen3-embedding:0.6b`가 모두 있는가
- [ ]  `./gradlew bootRun`이 성공하고, 기동 로그에 `RAG 시드 완료 — 신규 7건 / 스킵 0건` 이 찍히는가
- [ ]  **앱을 한 번 더 재시작**했을 때 `신규 0건 / 스킵 7건`으로 바뀌는가 (중복 방지 증명)
- [ ]  `application.yml`의 `spring.ai.vectorstore.pgvector.dimensions`가 **1024**이고, 임베딩 모델이 `qwen3-embedding:0.6b`(1024차원)로 **일치**하는가
- [ ]  `AssistantController` / `SupportController` 둘 다 Advisor 체인에 `ragAdvisor`가 등록되어 있는가
- [ ]  `"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"` 응답에 **정책 원문 수치**(기상 특보, +30분, 1,000원 쿠폰 등)가 포함되어 있는가

### B. 핵심 확인 — RAG 구성 요소

- [ ]  `RagConfig`에 `TokenTextSplitter` + `QuestionAnswerAdvisor` **2개 Bean**이 모두 존재하는가
- [ ]  `QuestionAnswerAdvisor.builder(...).order(20)`으로 **명시적으로 order가 지정**되어 있는가 (기본값 의존 X)
- [ ]  `SearchRequest`에 `topK`와 `similarityThreshold`가 **둘 다** 설정되어 있는가 (임계값 누락은 환각의 지름길)
- [ ]  `KnowledgeLoader`가 `ApplicationRunner`를 구현했는가 (`@PostConstruct` 아님)
- [ ]  `alreadyLoaded(faqId)`가 `filterExpression("faqId == '...'")` + `similarityThresholdAll()`을 사용하는가
- [ ]  `Document`의 metadata에 `faqId`, `title`, `category`가 들어가는가 — 이후 필터/디버깅/출처 추적에 필수

### C. 프롬프트와 Fallback

- [ ]  `BaedalPrompt`에 `[정책 인용 규칙]` 섹션이 존재하는가
- [ ]  해당 규칙에 (1) “Context 근거로만 답한다” (2) “없으면 상담원 연결” Fallback 문구 (3) “원문 수치 유지” (4) “상담 범위 밖 처리”가 모두 포함되는가
- [ ]  도메인 밖 질문(`"오늘 점심 뭐 먹을까요?"`)이 Fallback 문구로 처리되는가 (비빔밥 추천 등 환각 X)

### D. 심화 확인 — 설계 품질

- [ ]  Advisor 체인 순서가 `memory(10) → rag(20) → performance(100)`으로 **order 값이 근거 있게** 배치되어 있는가
- [ ]  README의 설계 결정 문서에 “왜 청크 800” / “왜 Top-K=4” / “왜 threshold=0.5” / “왜 Memory가 먼저”에 대한 **본인 언어의 근거**가 있는가 (복붙 설명이 아닌)
- [ ]  2단계 청크 실험에서 실험 전 **PgVector TRUNCATE**를 수행했는가 (안 하면 row가 섞여 실험 무의미)
- [ ]  실패 관찰(chunkSize=100에서 문맥 조각남, 프롬프트 규칙 제거 시 환각)이 **Context 블록 발췌 + LLM 응답 캡처**로 증명되어 있는가

---

## 흔한 실수 패턴

### 실수 1: 임베딩 모델과 `dimensions` 불일치

가장 조용히 터지는 실수. 에러 없이 **쓰레기 검색 결과**가 나온다.

**문제 코드 (application.yml):**

```yaml
spring:
ai:
ollama:
embedding:
model: qwen3-embedding:0.6b   # 1024차원
vectorstore:
pgvector:
dimensions:768               # 옛 nomic 기준, 불일치!
```

**개선 코드:**

```yaml
spring:
ai:
ollama:
embedding:
model: qwen3-embedding:0.6b   # 1024차원
vectorstore:
pgvector:
dimensions:1024              # 임베딩 모델 차원과 반드시 일치
```

**왜?** PgVector의 `vector_store.embedding` 컬럼은 `VECTOR(dimensions)`로 고정 차원이다.
`dimensions=768`로 잡아놓고 1024 차원 임베딩을 넣으면 insert 시점에 에러가 나지만, 운 나쁜 조합에서는 **오래된 데이터와 새 데이터가 섞여** 유사도 값이 말도 안 되게 낮게 나오는 “조용한 실패”가 발생한다.
앱 기동 시 기존 `vector_store` 테이블이 있으면 Spring AI가 재생성하지 않으므로, **모델을 바꿨다면(특히 차원이 다른 모델로) 테이블을 DROP 후 재기동**해야 한다. 예전 `nomic-embed-text`(768)에서 `qwen3-embedding:0.6b`(1024)로 옮길 때가 바로 이 경우다.

---

### 실수 2: `QuestionAnswerAdvisor.order()` 지정 누락

**문제 코드:**

```java
@Bean
public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
    return QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(SearchRequest.builder().topK(4).similarityThreshold(0.5).build())
            .build();   // order 미지정 → 기본값(0)에 가까움
}
```

**개선 코드:**

```java
@Bean
public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
    SearchRequest searchRequest = SearchRequest.builder()
            .topK(4)
            .similarityThreshold(0.5)
            .build();

    return QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .order(20)   // Memory(10) 뒤, Performance(100) 앞 — 명시적
            .build();
}
```

**왜?** order가 지정되지 않으면 RAG가 Memory보다 먼저 실행되어 “아까 그 주문” 같은 지시 대명사를 복원하지 못한 채 검색이 돌아간다.
임베딩되는 문구는 `"아까 그 주문 환불 돼요?"` 그 자체가 되어, 현재 주문 상태(DELIVERING 등)와 무관한 일반 환불 정책이 Top-K에 오르게 된다. **숙제 3단계의 관찰 포인트가 이것**이다.

---

### 실수 3: `similarityThreshold` 0으로 두기 (또는 누락)

**문제 코드:**

```java
SearchRequest.builder()
    .topK(4)
    // .similarityThreshold(???) — 누락
    .build();
```

또는

```java
SearchRequest.builder()
    .topK(4)
    .similarityThreshold(0.0)   // 사실상 필터 없음
    .build();
```

**개선 코드:**

```java
SearchRequest.builder()
    .topK(4)
    .similarityThreshold(0.5)   // 도메인 실험으로 0.5~0.7 튜닝
    .build();
```

**왜?** 임계값을 0 또는 누락하면 `"오늘 점심 뭐 먹을까요?"` 같은 도메인 밖 질문에도 **무관한 정책 문서가 Top-K에 꽉 차서** Context로 주입된다.
LLM은 프롬프트에 들어온 문서를 근거로 끌어다 쓰므로, “환불 정책에 따르면 점심은 비빔밥이…” 같은 괴상한 조합이 나올 수 있다.
`[정책 인용 규칙]`의 Fallback 문구만으로는 부족하며 **임계값 + 프롬프트 규칙의 2중 방어**가 필요하다.

---

### 실수 4: 중복 적재 방지가 `alreadyLoaded()` 누락/오작동

**문제 코드 A — 방지 로직 자체 없음:**

```java
for (Resource resource : resources) {
    FaqDocument faq = parse(resource);
    Document doc = new Document(faq.id(), faq.content(), Map.of(...));
    vectorStore.add(tokenTextSplitter.apply(List.of(doc)));   // 매 기동마다 누적
}
```

**문제 코드 B — filterExpression 문법 오류:**

```java
SearchRequest.builder()
    .query("정책")
    .topK(1)
    // similarityThresholdAll() 누락 → 기본 임계값에 걸려 항상 빈 리스트 반환
    .filterExpression("faqId = '" + faqId + "'")   // '=' 1개, Spring AI 필터 문법은 '=='
    .build();
```

**개선 코드:**

```java
private boolean alreadyLoaded(String faqId) {
    SearchRequest req = SearchRequest.builder()
            .query("정책")                           // 아무 값 OK — filter로만 판단
            .topK(1)
            .similarityThresholdAll()                 // 유사도로 거르지 않음
            .filterExpression("faqId == '" + faqId + "'")   // Spring AI 필터 문법: ==
            .build();
    return !vectorStore.similaritySearch(req).isEmpty();
}
```

**왜?** (1) `similarityThresholdAll()`이 없으면 기본 임계값에 막혀 이미 있어도 “없음”으로 판단되어 **또 적재**된다.
(2) Spring AI의 `FilterExpressionBuilder` 문법은 SQL이 아니라 `==`, `!=`, `>`, `in`을 쓴다.
(3) 재기동 시 `RAG 시드 완료 — 신규 0건 / 스킵 7건`이 찍히는지로 쉽게 검증 가능하다.

---

### 실수 5: Advisor 체인 순서 뒤집힘

**문제 코드:**

```java
// AssistantController
return builder
        .defaultAdvisors(ragAdvisor, memoryAdvisor, performanceAdvisor)   // RAG 먼저!
        .build()
        ...
```

또는 `RagConfig`에서 `.order(5)`로 지정해 실제 실행 순서가 Memory보다 앞선 경우.

**개선 코드:**

```java
return builder
        .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
        .build()
        ...
```

**왜?** `defaultAdvisors(...)`의 나열 순서는 **가독성**일 뿐 실제 실행 순서는 `Advisor.getOrder()` 값으로 정해진다. 그래서 두 가지를 모두 봐야 한다.
- `order` 값: `memory=10`, `rag=20`, `performance=100` → 낮을수록 먼저
- 나열 순서: 코드를 읽는 동료를 위해 order 오름차순으로 맞춰 두는 것이 관례

순서가 뒤집히면 3단계 숙제에서 관찰하듯이 2턴 대화(`"아까 그 주문 환불 돼요?"`)의 RAG 검색 쿼리가 **복원 전 원문**으로 들어가서 엉뚱한 정책을 Top-K에 올린다.

---

### 실수 6: `@PostConstruct`에서 `vectorStore.add()` 호출

**문제 코드:**

```java
@Component
@RequiredArgsConstructor
public class KnowledgeLoader {
    private final VectorStore vectorStore;

    @PostConstruct
    public void init() {
        // VectorStore Bean은 만들어졌지만 DataSource/schema 초기화가
        // 완전히 끝났다는 보장이 없다 — 타이밍에 따라 테이블 없음 에러
        vectorStore.add(...);
    }
}
```

**개선 코드:**

```java
@Component
@RequiredArgsConstructor
public class KnowledgeLoader implements ApplicationRunner {
    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenTextSplitter;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // ApplicationContext 기동 완료 후 실행 — 안전
        ...
    }
}
```

**왜?** `@PostConstruct`는 Bean 초기화 콜백이라 `initialize-schema=true`로 테이블을 만드는 타이밍과 경쟁 상태가 될 수 있다. `ApplicationRunner`는 Spring Boot의 **기동 완료 훅**이므로 VectorStore가 완전히 준비된 상태에서 안전하게 `add()`를 호출한다.

---

### 실수 7: metadata에 출처 정보 누락

**문제 코드:**

```java
Document doc = new Document(faq.id(), faq.content(), Map.of());   // metadata 비어 있음
```

**개선 코드:**

```java
Document doc = new Document(
        faq.id(),
        faq.content(),
        Map.of(
                "faqId", faq.id(),
                "title", faq.title(),
                "category", faq.category()   // 필터 검색 + 로그 추적 + 응답 출처 노출에 필수
        ));
```

**왜?** metadata가 비면 (1) 중복 방지용 `filterExpression("faqId == '...'")`이 걸 키가 없고, (2) DEBUG 로그에서 “이 Context 블록이 어느 문서에서 왔나” 추적이 불가능하며, (3) 나중에 응답에 출처(“참고: 환불정책 제3조”)를 달고 싶을 때 metadata에서 끄집어낼 정보가 없다.
4단계 AI 코드 리뷰에서 “출처 표시 없음”은 단골 결함이다.

---

### 실수 8: 청크 실험 시 TRUNCATE 누락

**문제 시나리오:**

```bash
# 실험 A (chunkSize=800) 실행 — row 수 7
# RagConfig 수정: chunkSize=100
./gradlew bootRun   # KnowledgeLoader가 "스킵 7건"으로 끝냄 — 재적재 안 됨!
# row 수 확인 → 여전히 7, 청크 100으로 쪼개지지 않은 상태
```

**개선 절차:**

```bash
docker exec -it baedal-pgvector psql -U baedal -d baedal \
  -c "TRUNCATE TABLE vector_store;"   # 실험 전 반드시 비우기
# RagConfig 수정 후 재기동 → 신규 7건(실제로는 쪼개져 더 많은 청크)으로 재적재
```

**왜?** 중복 방지가 잘 동작하는 반작용으로, 청크 설정을 바꿔도 `alreadyLoaded(faqId)`가 true를 돌려주면서 `vectorStore.add()`가 호출되지 않는다.
실험한 줄 알았는데 **기존 청크로 평가한** 결과가 되어 숙제 2단계가 전부 의미 없어진다. 루브릭에도 `-5점 감점` 항목이다.

---

### 실수 9: `[정책 인용 규칙]`을 프롬프트에 빠뜨리거나, Fallback 문구만 있고 “원문 수치 유지” 규칙이 없음

**문제 코드 (BaedalPrompt):**

```
[정책 인용 규칙]
- 모르면 "상담원 연결해 드리겠습니다"라고 답합니다.
```

**개선 코드:**

```
[정책 인용 규칙]
- 환불, 취소, 배달 지연 보상, 쿠폰 관련 질문은 반드시 제공된 Context(배달 정책/FAQ 문서)를 근거로만 답합니다.
- Context에서 답을 찾을 수 없으면 추측하지 말고 이렇게 답합니다:
  "해당 내용은 확인이 필요합니다. 상담원 연결로 도와드리겠습니다."
- 정책을 인용할 때는 원문의 수치/조건(예: "60분 이상", "24시간 이내", "1,000원 쿠폰")을
  그대로 사용합니다. 임의로 반올림하거나 단순화하지 않습니다.
- 단순 인사, 잡담, 상담 범위 밖 질문에는 Context를 인용하지 않고
  "고객님, 저는 주문/배달/환불 관련 상담을 도와드리고 있어요"로 범위를 안내합니다.
```

**왜?** Fallback만 있으면 LLM이 **수치를 제멋대로 반올림**한다. “24시간 이내” → “하루 안에”, “1,000원 쿠폰” → “천원 정도 쿠폰”처럼 뉘앙스가 달라져 감사 추적이 불가능해진다. “원문 그대로” 규칙이 결정적이다.

---

### 실수 10: 한 Controller에만 `ragAdvisor`를 달고 끝내기

**문제:** `AssistantController`에는 달았는데 `SupportController`는 안 건드림. 숙제 루브릭의 “두 Controller 모두” 항목에서 감점.

**체크 방법:** 두 컨트롤러 모두에서 `private final QuestionAnswerAdvisor ragAdvisor;` 필드가 있고, `defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)` 라인이 들어있는지 grep으로 한 번에 확인.

---

## 모범 답안 핵심 발췌

전체 코드는 `week4/example-code/`를 참조. 아래는 리뷰 시 기준으로 삼을 핵심 조각.

### `RagConfig` — Bean 2개

```java
@Configuration
public class RagConfig {

    private static final int TOP_K = 4;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(
                800,    // chunkSize
                350,    // minChunkSizeChars
                5,      // minChunkLengthToEmbed
                10_000, // maxNumChunks
                true    // keepSeparator
        );
    }

    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();

        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .order(20)   // Memory(10) 뒤, Performance(100) 앞
                .build();
    }
}
```

**설계 포인트:**
- `TOP_K`, `SIMILARITY_THRESHOLD`를 상수로 빼서 **실험 시 한 곳만 바꾸면 되도록** 했다.
- `order(20)` 명시 — Memory/Performance Advisor와의 협업을 코드 자체가 설명한다.
- `TokenTextSplitter`의 5개 파라미터는 **의미를 주석으로 남긴다** — 다음 리뷰어가 바로 이해한다.

### `KnowledgeLoader` — 중복 방지 적재

```java
@Override
public void run(ApplicationArguments args) throws Exception {
    Resource[] resources = new PathMatchingResourcePatternResolver()
            .getResources("classpath:/knowledge/*.md");

    int loaded = 0, skipped = 0;
    for (Resource resource : resources) {
        FaqDocument faq = parse(resource);

        if (alreadyLoaded(faq.id())) {
            skipped++;
            continue;
        }

        Document doc = new Document(
                faq.id(), faq.content(),
                Map.of("faqId", faq.id(), "title", faq.title(), "category", faq.category())
        );
        List<Document> chunks = tokenTextSplitter.apply(List.of(doc));
        vectorStore.add(chunks);
        loaded++;
        log.info("[KnowledgeLoader] 적재 완료 — id={} / 청크={}개", faq.id(), chunks.size());
    }
    log.info("[KnowledgeLoader] RAG 시드 완료 — 신규 {}건 / 스킵 {}건 / 총 {}건",
            loaded, skipped, resources.length);
}

private boolean alreadyLoaded(String faqId) {
    SearchRequest req = SearchRequest.builder()
            .query("정책")
            .topK(1)
            .similarityThresholdAll()
            .filterExpression("faqId == '" + faqId + "'")
            .build();
    return !vectorStore.similaritySearch(req).isEmpty();
}
```

**설계 포인트:**
- `ApplicationRunner` — 기동 완료 후 실행해 schema 초기화와의 경쟁 조건을 회피.
- metadata에 `faqId`를 심어서 **중복 방지 키**로 재활용 (별도 테이블 불필요, 교육용 단순 구현).
- `신규 / 스킵 / 총` 3개 수치 로그가 **진단의 1차 단서** — “0건이면 문제”를 눈에 띄게 한다.

### Advisor 체인 — Controller

```java
return builder
        .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
        .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
        .defaultTools(orderTools)
        .build()
        .prompt()
        .user(req.message())
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
        .call()
        .content();
```

**설계 포인트:**
- order 값(10/20/100)과 나열 순서를 **일부러 일치시켜** 코드를 읽는 순서 = 실행 순서가 되게 했다.
- `ChatMemory.CONVERSATION_ID`는 이 호출에 한해서만 지정 — Controller 레벨에서 세션별 메모리가 분리되도록.

### `[정책 인용 규칙]` — 프롬프트

```
[정책 인용 규칙]
- 환불, 취소, 배달 지연 보상, 쿠폰 관련 질문은 반드시 제공된 Context를 근거로만 답합니다.
- Context에서 답을 찾을 수 없으면 추측하지 말고 이렇게 답합니다:
  "해당 내용은 확인이 필요합니다. 상담원 연결로 도와드리겠습니다."
- 정책을 인용할 때는 원문의 수치/조건을 그대로 사용합니다. 임의로 반올림하지 않습니다.
- 여러 정책이 관련될 때는 고객의 상황(주문 상태, 경과 시간)에 가장 맞는 정책을 선택합니다.
- 단순 인사, 잡담, 상담 범위 밖 질문에는 Context를 인용하지 않고
  "고객님, 저는 주문/배달/환불 관련 상담을 도와드리고 있어요"로 범위를 안내합니다.
```

**설계 포인트:**
- (1) Context 근거만 + (2) Fallback 문구 + (3) 원문 수치 유지 + (4) 우선순위 + (5) 범위 밖 처리 — **5가지 축이 모두** 담겨 있다.
- `similarityThreshold=0.5` 와 합쳐 **2중 방어**를 완성.

---

## 채점 기준(rubric)과의 연결

리뷰어가 리뷰 코멘트에 “몇 점짜리 결함인지”를 암시해 주면 피리뷰어가 우선순위를 잡기 쉽다.

| rubric 항목 | 배점 | 리뷰에서 중점적으로 볼 것 |
| --- | --- | --- |
| **1단계 · 프로젝트 실행** | 5 | 기동 로그 `신규 N건 / 스킵 N건` 스크린샷 또는 복붙 여부 |
| **1단계 · RagConfig 2 Bean** | 6 | `.order(20)` 명시 + `topK` / `similarityThreshold` **둘 다** 세팅 |
| **1단계 · KnowledgeLoader 적재 + 중복 방지** | 5 | `alreadyLoaded()` 존재 + 재기동 시 `스킵 7건` 증명 로그 |
| **1단계 · Advisor 체인 + 프롬프트 규칙** | 4 | 두 Controller 모두 + `[정책 인용 규칙]` 5가지 축 |
| **1단계 · 시나리오 5종 검증** | 5 | 5개 응답 + `Context:` 블록 발췌 + `vector_store` 조회 결과 |
| **1단계 · 설계 결정 문서** | 5 | 청크 / Top-K / order / threshold **4가지 “왜?”** |
| **2단계 · 3가지 청크 실험** | 5 | TRUNCATE 후 재적재 증명 (row 수 변화) |
| **2단계 · 정량 비교 표** | 6 | row 수 / 평균 입력 토큰 / 답변 품질 3열 모두 채움 |
| **2단계 · 문맥 조각남 캡처** | 5 | chunkSize=100에서 **Context 블록 발췌 + LLM 응답** 둘 다 |
| **2단계 · Fallback 없는 환각 관찰** | 5 | `[정책 인용 규칙]` 주석 처리 전/후 비교 캡처 |
| **3단계 · Advisor 순서 실험** | 6 | `order(5)`로 뒤바꿔 2턴 대화 차이 증거 포착 |
| **4단계 · 토큰 비교 + AI 코드 리뷰** | 15 | (a)(b)(c) 입력 토큰 수치 + AI 원본 + 결함 3개 + 개선 방안 |

각 항목의 **만점/절반/0점** 기준은 `week4/mission/rubric.md` 참조.

---

## 리뷰 코멘트 예시

> 톤: “틀렸다”가 아니라 “더 나은 방법”으로 안내한다. 동료 리뷰이므로 **함께 실험해 보자**는 제안형이 좋다.
>

| 상황 | 리뷰 코멘트 예시 |
| --- | --- |
| `.order()` 누락 | “여기 `order()`가 빠져 있어 기본값으로 들어갈 것 같아요. `ragAdvisor`가 `memoryAdvisor(10)`보다 먼저 실행되면 시나리오 5의 ’아까 그 주문’이 복원 전에 임베딩돼서 엉뚱한 정책이 Top-K에 오르더라고요. `order(20)` 명시해 보시는 걸 추천드려요.” |
| `similarityThreshold` 누락 | “임계값을 안 잡아 두셨네요. 도메인 밖 질문(예: ‘오늘 점심’)에서 Fallback이 잘 나오나요? `0.5`부터 시작해서 3단계 실험할 때 0.3 / 0.7로 움직여 보면 차이가 크게 느껴집니다.” |
| 중복 방지 로직 누락 | “앱을 재시작했을 때 `vector_store` row가 두 배로 늘지 않나요? `alreadyLoaded(faqId)`에 `filterExpression("faqId == '...'")` + `similarityThresholdAll()`로 막는 패턴이 example-code에 있어요.” |
| metadata 비어 있음 | “`Document(id, content, Map.of())`로 metadata를 비워두면 나중에 ’이 답의 출처가 어느 FAQ인가’를 추적할 때 쓸 수 있는 정보가 없어요. `faqId`/`title`/`category`만 넣어둬도 DEBUG 로그 가독성이 확 올라갑니다.” |
| 청크 실험 TRUNCATE 누락 | “2단계 실험할 때 TRUNCATE 없이 `chunkSize`만 바꾸셨는데, 중복 방지 때문에 재적재가 안 되고 있어요. `docker exec ... psql ... -c 'TRUNCATE TABLE vector_store;'` 한 번 돌리고 다시 재기동해 보시면 row 수가 변하는 게 보일 거예요.” |
| `dimensions` 불일치 | “임베딩은 `qwen3-embedding:0.6b`(1024)인데 `application.yml`의 `dimensions: 768`이 옛 nomic 기준 그대로 남아 있네요. 기동은 되더라도 검색 품질이 조용히 망가지는 조합이에요. `dimensions: 1024`로 맞추고, 차원 바뀌었으니 테이블 DROP 후 재기동까지 세트로 해주세요.” |
| 프롬프트 규칙 빈약 | “`[정책 인용 규칙]`이 ‘Fallback 문구’ 하나만 있어요. 원문 수치 유지 규칙을 넣지 않으면 LLM이 ‘24시간 이내’ → ’하루 내에’로 임의 반올림하더라고요. 5가지 축(근거만/Fallback/원문 수치/우선순위/범위 밖) 다 넣어 보는 걸 추천드려요.” |
| SupportController만 적용 | “AssistantController에는 `ragAdvisor`가 있는데 SupportController에는 빠져 있네요. 구조화 응답 API에도 RAG가 필요한 케이스가 있어서 둘 다 붙이는 게 숙제 요구사항입니다.” |
| 시나리오 검증 캡처 부족 | “시나리오 5종을 돌리셨는데 응답 본문만 있고 `Context:` 블록 발췌가 없어요. DEBUG 로그에서 `QuestionAnswerAdvisor` 섹션 한 번만 복붙해 붙여주시면 ’정책 원문이 진짜로 주입됐다’가 증명됩니다.” |
| 설계 결정 문서에 “왜?”가 없음 | “’청크 크기를 800으로 정했다’라고만 쓰여 있어요. 정책 문서가 조항 단위로 짧게 끊어져 있다는 도메인 특성이 근거가 될 수 있을 것 같은데, 한 줄 더 보태주시면 리뷰어 입장에서 ’아 이 사람 근거가 있구나’가 보여요.” |
| 2단계 chunkSize=100 관찰 빈약 | “‘잘 안 됐다’로만 끝나는 건 좀 아쉬워요. 어느 청크가 Top-K에 올라왔고, 거기 수치가 빠져 있어서 LLM이 어떻게 뭉뚱그렸는지 Context 블록과 응답을 나란히 붙여 보시면 관찰 기록의 품질이 확 올라갑니다. rubric의 ’좋은 예’ 설명이 참고될 거예요.” |
| Advisor 순서 실험 미수행 | “3단계에서 순서를 뒤바꾸는 실험을 ’해봤다’고만 쓰여 있는데, 실제로 `order(5)`로 돌려서 2턴 대화를 캡처한 Context 블록이 있으면 좋을 것 같아요. 이게 가장 극적으로 차이가 나는 실험이거든요.” |

---

## 리뷰 진행 팁 (동료 리뷰 상황)

1. **먼저 빌드·실행을 내 PC에서 한 번 해 본다** — README만 보고 리뷰하면 “돌아간다”가 거짓말인 경우를 못 잡는다.
2. **DEBUG 로그를 같이 본다** — RAG는 “입력 프롬프트에 뭐가 들어갔는지”가 전부다. `Context:` 블록을 눈으로 한 번이라도 본 리뷰어와 안 본 리뷰어의 코멘트 품질은 다르다.
3. **질문 형식으로 코멘트를 단다** — “왜 `order=20`인가요?” 식으로 물어보면 피리뷰어가 스스로 근거를 정리하는 계기가 된다.
4. **rubric 배점을 암시한다** — “이거 1단계 `RagConfig 2 Bean`(6점) 항목에 걸릴 것 같아요” 식으로 가리키면 우선순위가 명확해진다.
5. **칭찬할 곳은 구체적으로 칭찬한다** — “`alreadyLoaded`에 `similarityThresholdAll()`까지 챙기신 거 좋네요” 같은 한 줄이 다음 주차에 설계 문서 품질을 올린다.