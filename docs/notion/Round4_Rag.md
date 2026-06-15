# Round 4 — RAG(Retrieval-Augmented Generation)로 배달 정책/FAQ 지식 연동

# Round 4 — RAG로 배달 정책/FAQ 지식 연동

## 이번 라운드에 배우는 것

Round 3까지 만든 에이전트는 “그거 취소해줘”까지는 잘 해결합니다. 그런데 이런 질문은 어떨까요?

```
고객: 배달 완료 후에도 환불 받을 수 있나요?
 봇 : (? — 배달의 환불 정책이 뭐였더라…)

고객: 비 오는 날 배달이 늦으면 보상 받을 수 있나요?
 봇 : (? — "비 오는 날"에 관한 공식 정책이 있었나?)

고객: 쿠폰 적용이 안 돼요. 중복 사용 가능한가요?
 봇 : (? — 쿠폰 중복 규칙이 뭐였지?)
```

이 질문들은 **Tool로도, Memory로도 풀 수 없습니다**. 필요한 건 배달의 **실제 정책 문서**입니다. Round 4는 그 정책 문서를 검색해서 답하게 만드는 라운드입니다.

- 왜 RAG가 필요한가를 LLM의 학습 데이터 한계 / 최신성 / 도메인 특화 지식의 세 관점에서 설명한다
- RAG의 두 파이프라인 — **인덱싱(Indexing) vs 검색(Retrieval)** — 을 말로 설명한다
- Spring AI의 `VectorStore` / `EmbeddingModel` / `TokenTextSplitter` / `QuestionAnswerAdvisor`의 역할을 구분한다
- PgVector를 Docker로 띄우고, `initialize-schema: true` 하나로 스키마가 자동 생성되는 과정을 관찰한다
- 문서 **청킹(Chunking)** 의 크기/오버랩 트레이드오프를 설명하고, 값을 바꿔 실제 검색 품질 변화를 관찰한다
- Round 3의 `MessageChatMemoryAdvisor`와 `QuestionAnswerAdvisor`가 **같은 체인** 위에서 어떻게 협업하는지 로그로 확인한다
- 검색 결과가 없을 때의 **Fallback 전략**(환각 방지)을 시스템 프롬프트와 유사도 임계값으로 설계한다

> 🎯
**이번 라운드의 한 줄 메시지**: RAG를 “연결하는 것”은 Advisor 한 줄이다. 정작 어려운 건 **얼마나 쪼갤지, 몇 건을 가져올지, 얼마 이상의 유사도를 신뢰할지, 모를 땐 어떻게 답할지**의 판단이다.
>

---

## 학습 목표

이번 라운드가 끝나면 다음을 할 수 있습니다.

- [ ]  **왜 RAG가 필요한가**를 LLM의 학습 데이터 한계, 최신성, 도메인 특화 지식의 세 관점에서 설명할 수 있다
- [ ]  RAG의 두 파이프라인 — 인덱싱 vs 검색 — 을 그림 없이 말로 설명할 수 있다
- [ ]  임베딩 벡터의 직관을 “의미가 가까우면 거리가 가깝다”로 설명하고, 키워드 검색과 어떻게 다른지 말할 수 있다
- [ ]  `VectorStore` / `EmbeddingModel` / `TokenTextSplitter` / `QuestionAnswerAdvisor`의 역할을 구분할 수 있다
- [ ]  청킹의 크기/오버랩 트레이드오프를 설명하고, 값을 바꿔 검색 품질 변화를 관찰할 수 있다
- [ ]  Memory Advisor와 QA Advisor가 같은 체인 위에서 어떻게 협업하는지 로그로 설명할 수 있다
- [ ]  검색 결과가 없을 때의 Fallback 전략(유사도 임계값 + 시스템 프롬프트)을 설계할 수 있다

---

## 1부. 왜 RAG가 필요한가 — LLM이 모르는 것을 답하게 하는 법

### 1.1 Round 3까지의 한계

지금까지 우리 에이전트는 “그거 취소해줘”까지는 잘 해결하게 됐습니다. 하지만 위에서 본 환불·지연 보상·쿠폰 질문은 Tool로도, Memory로도 풀 수 없습니다. 필요한 건 배달의 **실제 정책 문서**입니다.

> 🎯
**핵심 메시지**: LLM은 만능이 아니다. 배달의 환불 규정, 지연 보상 기준, 쿠폰 정책 같은 **도메인 특화 지식**은 학습 데이터에 들어있을 리가 없다. RAG는 “LLM이 모르는 지식을 검색해서 프롬프트에 넣어준다”는 단순한 아이디어이며, 그래서 강력하다.
>

### 1.2 RAG가 해결하는 세 가지 문제

| 문제 | 예시 | RAG 없이는 |
| --- | --- | --- |
| **LLM 학습 데이터에 없는 지식** | 배달 환불 정책, 지연 보상 기준 | LLM이 그럴듯하게 꾸며낸 “환각(hallucination)” 응답 |
| **최신성** | 이번 달 이벤트, 어제 개정된 쿠폰 정책 | 파인튜닝으로 따라잡을 수 없는 비용/주기 |
| **출처 추적 가능성** | “이 답의 근거 문서가 뭔가?” 감사 요구 | LLM 응답은 black-box — 근거 제시 불가 |

### 1.3 그냥 Tool로 검색 API를 만들면 안 되는가?

좋은 질문입니다. 실제로 그것도 하나의 방법입니다. 그런데 차이를 보면 RAG가 더 자연스러운 이유가 드러납니다.

| 방식 | 설계 | 언제 쓰나 |
| --- | --- | --- |
| **Tool 방식** | `searchPolicy(keyword)` Tool을 만들고 LLM이 호출 | 고객이 “환불 검색”이라고 명시적으로 요청할 때 |
| **RAG 방식** | 사용자 질문을 **자동으로** 임베딩 → 검색 → 프롬프트 주입 | 고객 질문 자체가 “이미 무엇에 대한 검색”인 경우 (즉, 대부분의 질문) |

상담 도메인에서 고객은 “이거 환불 검색해줘”라고 말하지 않습니다. 그냥 “배달 완료됐는데 환불 돼요?”라고 합니다. RAG는 **검색 의도를 감지할 필요 없이** 모든 질문에 대해 관련 지식을 찾아 자동으로 프롬프트에 꽂아 줍니다.

### 1.4 RAG의 두 파이프라인

RAG 시스템은 두 개의 완전히 다른 파이프라인으로 구성됩니다. 이 분리가 중요합니다.

```
  [인덱싱 (Indexing) 파이프라인]   ← 앱 기동 시 또는 문서 갱신 시 1회
  ────────────────────────────────
  FAQ/정책 MD 파일
       │
       ▼  (파일 읽기)
  Document
       │
       ▼  (TokenTextSplitter)
  청크들 (Document list)
       │
       ▼  (EmbeddingModel — qwen3-embedding:0.6b)
  각 청크의 벡터 + 원본 텍스트 + metadata
       │
       ▼
  VectorStore (PgVector)

  [검색 (Retrieval) 파이프라인]   ← 매 요청마다 실행
  ───────────────────────────────
  고객 질문 "비 오는 날 환불 되나요?"
       │
       ▼  (EmbeddingModel — 같은 모델)
  질문 벡터
       │
       ▼  (VectorStore.similaritySearch)
  유사도 상위 K개 문서
       │
       ▼  (QuestionAnswerAdvisor가 프롬프트 앞에 붙임)
  Context + 원래 질문 → LLM → 응답
```

> 💡
**임베딩 모델을 두 파이프라인에서 같은 것으로 써야 한다** — 인덱싱 때 `qwen3-embedding:0.6b`로 벡터화했으면 검색 때도 반드시 `qwen3-embedding:0.6b`여야 한다. 서로 다른 임베딩 모델이 만든 벡터 공간은 통하지 않는다.
>

### 1.5 임베딩의 직관

임베딩 모델은 문장을 **고정된 차원의 벡터**로 변환합니다. `qwen3-embedding:0.6b`는 (Ollama 기본 설정에서) 1024차원이며, 문장의 의미를 1024개의 실수로 압축합니다.

핵심 성질: **의미가 가까운 문장은 벡터 공간에서 거리가 가깝다.**

```
"환불 받을 수 있나요?"        → [0.21, -0.15, 0.42, ...]  (1024개)
"돈 돌려받을 수 있어요?"      → [0.19, -0.14, 0.41, ...]  (1024개)   ← 거리 가까움
"치킨 2마리 주문할게요"       → [0.75, -0.02, -0.33, ...] (1024개)   ← 거리 멀음
```

그래서 RAG는 키워드 검색과 달리 “환불”이라는 단어가 없어도 “돈 돌려받다”를 찾아낼 수 있습니다. 이게 벡터 검색의 힘입니다.

### 1.6 왜 청킹(Chunking)이 필요한가

정책 문서를 통째로 임베딩하면 어떻게 될까요?

- 문서 하나가 A4 10장이면 → 벡터 하나에 의미 10장 치가 압축됨 → 질문과의 유사도가 뭉툭해짐
- 문서 일부 조각만 답에 필요해도 → 전체를 프롬프트에 넣으면 토큰 낭비 + 노이즈

해결: 문서를 **적당한 크기의 청크(chunk)** 로 쪼개 각각을 독립적으로 임베딩합니다.

| 청크 크기 | 장점 | 단점 |
| --- | --- | --- |
| **작게 (200~400 토큰)** | 검색 정확도 높음 | 문맥이 조각나서 “앞뒤 맥락”이 잘림 |
| **중간 (600~1000 토큰)** | 균형 | 튜닝 포인트가 많음 |
| **크게 (1500~3000 토큰)** | 맥락 보존 | 유사도 뭉툭, 토큰 낭비 |

**오버랩(overlap)** 은 청크 경계에서 맥락이 끊기는 문제를 완화합니다. 예: 청크 800 / 오버랩 200 이면 인접 청크가 200 토큰을 공유합니다.

> 🎯
**핵심 메시지**: 청크 크기와 오버랩은 RAG에서 **가장 많이 튜닝하는 값**이다. 한 번에 맞추려 하지 말고, 실제 질문과 검색 결과를 보면서 조정하는 실험이다.
>

---

## 2부. RAG 3구성요소 — EmbeddingModel / VectorStore / QuestionAnswerAdvisor

### 2.1 EmbeddingModel — 문장을 벡터로

Round 1의 `ChatModel`과 짝을 이루는 개념입니다.

| 인터페이스 | 역할 | 비유 |
| --- | --- | --- |
| `ChatModel` | 프롬프트 → 텍스트 응답 | “대화 API” |
| `EmbeddingModel` | 텍스트 → 고정 차원 실수 벡터 | “의미 API” |

Spring AI 1.0은 `OllamaEmbeddingModel`을 자동 구성합니다 — `application.yml`의 `spring.ai.ollama.embedding.model` 값만 맞추면 됩니다.

```yaml
spring:
ai:
ollama:
embedding:
model: qwen3-embedding:0.6b   # 반드시 사전에 'ollama pull' 필요
```

> 💡
**왜 임베딩 모델을 Ollama로 통일하는가** — API Key 불필요, 오프라인 가능, 비용 0. 교육용 환경에서는 OpenAI `text-embedding-3-small` 같은 클라우드 임베딩 대신 로컬 임베딩으로 “왕복”을 다 로컬에서 해결한다. 다만 품질은 클라우드 임베딩보다 조금 낮으므로 실무에서는 비교 테스트가 필요하다.
>

### 2.2 VectorStore — 벡터 저장 + 유사도 검색

```java
public interface VectorStore extends DocumentWriter {
    void add(List<Document> documents);
    void delete(List<String> idList);
    List<Document> similaritySearch(SearchRequest request);
    // ...
}
```

핵심은 `similaritySearch(request)` 하나입니다. 이 메서드가 질문 벡터와 가장 가까운 `topK`개 문서를 돌려줍니다.

Spring AI 1.0이 지원하는 VectorStore는 17종 이상 — Redis, Elasticsearch, Milvus, Chroma, PgVector, Weaviate, OpenSearch … 전환 비용이 거의 없습니다(application.yml + 의존성 교체).

**우리는 PgVector를 쓴다.** 이유:
- Docker 이미지 한 줄로 기동
- PostgreSQL이므로 기존 JDBC 지식이 그대로 통함
- 상담 이력/주문 DB와 **같은 인스턴스 공유 가능** (Round 3 JDBC Chat Memory도 여기로 합칠 수 있음)

### 2.3 TokenTextSplitter — 문서 청킹

```java
new TokenTextSplitter(
    800,    // chunkSize: 목표 청크 크기(토큰)
    350,    // minChunkSizeChars: 이보다 작으면 앞 청크에 병합
    5,      // minChunkLengthToEmbed: 이보다 짧으면 임베딩 제외
    10_000, // maxNumChunks
    true    // keepSeparator (문단 구분자 유지)
);
```

배달 정책 문서는 “조항 단위로 이미 잘 끊어져 있는” 형태라 기본값으로 대부분 잘 동작합니다. 블로그 글/장문 PDF를 임베딩할 때는 청크 크기를 훨씬 공격적으로 튜닝해야 합니다.

### 2.4 QuestionAnswerAdvisor — 검색과 프롬프트 주입의 자동화

이게 RAG를 **한 줄로 붙일 수 있는** 이유입니다.

```java
@Bean
public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
    SearchRequest searchRequest = SearchRequest.builder()
            .topK(4)
            .similarityThreshold(0.5)
            .build();

    return QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .order(20)  // Memory(10) 뒤, Performance(100) 앞
            .build();
}
```

**이 Advisor가 하는 일:**

1. 사용자 질문을 받아 `EmbeddingModel`로 벡터화
2. `vectorStore.similaritySearch(request)` 호출 → Top-K 문서
3. 시스템 프롬프트 뒤에 `Context:\n{문서1}\n{문서2}...` 블록 자동 추가
4. 그 프롬프트로 LLM 호출

우리는 **체인에 등록만** 하면 됩니다. 매 요청마다 직접 검색 코드를 쓸 필요가 없어요.

### 2.5 ChatClient에 연결

```java
return builder
        .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
        .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)  // Round 4: ragAdvisor 추가
        .defaultTools(orderTools)
        .build()
        .prompt()
        .user(req.message())
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
        .call()
        .content();
```

> 💡
**Advisor 체인 순서의 의미** (order 낮을수록 먼저 실행)
- `MessageChatMemoryAdvisor(10)` — 이전 대화 이력 주입
- `QuestionAnswerAdvisor(20)` — RAG 검색 결과 주입
- `PerformanceLoggingAdvisor(100)` — 최종 호출 시간 로깅
>
>
> Memory가 먼저 붙어야 “아까 그 주문의 환불 정책은?” 같은 질문에서 Q&A가 올바른 검색 쿼리를 만들 수 있다.
>

> ⚠️
**Round 2에서 배운 빌더 누적 함정**을 잊지 마세요. 핸들러 메서드 안에서 매 요청마다 `.defaultAdvisors(...)`를 호출하면 Advisor가 누적 등록됩니다. 컨트롤러 단위 고정 설정은 생성자에서 한 번만 build해 `ChatClient`로 보관하세요.
>

### 2.6 라이브 데모 — 벡터 검색이 실제로 뭘 보는가

DEBUG 레벨로 “검색 로그”를 다음 예제로 확인합니다.

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: live-1" \
  -d '{"message":"배달 완료 후에도 환불 받을 수 있나요?"}'
```

**관찰 포인트:**

| 순서 | 콘솔 로그에서 찾아야 할 것 |
| --- | --- |
| 1 | `QuestionAnswerAdvisor` 로그 — 검색 요청이 발생했는지 |
| 2 | Top-K 결과의 `metadata.faqId` — `refund-after-delivered`, `refund-basic` 가 상위에 와야 정상 |
| 3 | 프롬프트에 포함된 `Context:` 블록 — 원문 일부가 그대로 보임 |
| 4 | 응답의 수치 — “배달 완료 후 **24시간 이내**”, “누락/오배송 시 **부분 환불**” 같은 원문 표현 |

> 🎯
**체크포인트**: 콘솔 DEBUG 로그에서 `Context:` 블록의 길이가 클수록 입력 토큰이 증가한다. Memory와 RAG가 같이 붙어 있으면 입력 토큰이 확 커진다. 그게 RAG의 대가다 — “정확도를 돈(토큰)으로 산다”는 감각을 가져야 한다.
>

---

## 3부. 인덱싱 파이프라인 — 어떻게 문서를 넣는가

### 3.1 왜 ApplicationRunner인가

```java
@Component
@RequiredArgsConstructor
public class KnowledgeLoader implements ApplicationRunner {
    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenTextSplitter;

    @Override
    public void run(ApplicationArguments args) { ... }
}
```

대안들이 있었습니다.

| 위치 | 문제 |
| --- | --- |
| `@PostConstruct` | VectorStore의 DataSource가 완전히 준비되지 않았을 수 있음 |
| `CommandLineRunner` | 동일한 타이밍에 실행 — 상관없음 |
| `ApplicationReadyEvent` | OK. 여러 Runner가 있을 때 순서 제어가 덜 깔끔 |
| **`ApplicationRunner`** | ApplicationContext 기동 완료 후 실행. Spring Boot의 표준 초기화 훅 |

> 💡
**왜 `@PostConstruct`가 아닌가** — `@PostConstruct`는 Bean 초기화 순서에 얽혀있다. VectorStore Bean의 `initialize-schema`가 끝나기 전에 `add()`가 호출될 위험이 있다. ApplicationRunner는 기동 완료 후 실행되므로 안전하다.
>

### 3.2 파일명 컨벤션 — 구조가 코드를 단순하게 만든다

파일명: `{category}__{id}.md`

예: `refund__refund-basic.md`

- `category`: 정책 카테고리 (refund, delivery-delay, coupon, cancel, account)
- `id`: 전역 고유 식별자 (중복 방지 키)

이 컨벤션 덕에 파싱 코드가 단순합니다.

```java
int sep = base.indexOf("__");
String category = base.substring(0, sep);
String id       = base.substring(sep + 2);
```

### 3.3 Document로 변환

Spring AI의 `Document`는 RAG의 기본 단위입니다.

```java
Document doc = new Document(
    faq.id(),                          // id (UUID 아니어도 OK)
    faq.content(),                     // 본문 — 임베딩 대상
    Map.of(
        "faqId", faq.id(),
        "title", faq.title(),
        "category", faq.category()     // 검색 시 filter로 활용
    )
);
```

`metadata`는 VectorStore에 함께 저장되어 **필터 검색**에 쓸 수 있습니다. 예: “환불 카테고리 안에서만 검색” — `filterExpression("category == 'refund'")`.

### 3.4 청킹 적용

```java
List<Document> chunks = tokenTextSplitter.apply(List.of(doc));
vectorStore.add(chunks);
```

청크들은 원본 metadata를 **상속** 받습니다. 즉 한 문서가 3청크로 쪼개져도 셋 다 `faqId=refund-basic`이 붙습니다.

### 3.5 중복 적재 방지

앱을 재기동할 때마다 같은 문서가 또 쌓이면 곤란합니다. 해결:

```java
private boolean alreadyLoaded(String faqId) {
    SearchRequest req = SearchRequest.builder()
            .query("정책")                     // 아무 쿼리나 — filter로만 걸러짐
            .topK(1)
            .similarityThresholdAll()           // 유사도 임계값 없음
            .filterExpression("faqId == '" + faqId + "'")
            .build();
    return !vectorStore.similaritySearch(req).isEmpty();
}
```

**왜 이 방법을 쓰는가** — PgVector의 `Document`에는 id가 있지만, Spring AI의 `VectorStore` 인터페이스에는 “id로 한 건 조회”가 없습니다. 필요한 것은 “이미 있는지”의 yes/no 뿐이므로 `similaritySearch` + metadata filter로 충분합니다.

> 💡
프로덕션에서는 별도의 `seed_audit` 테이블을 두거나, 문서 해시(SHA-256)를 metadata에 넣어 “내용이 바뀌면 재적재”하는 전략이 낫다. 여기서는 교육용 단순 구현이다.
>

### 3.6 라이브 데모 — 중복 방지 & 카테고리 필터

```bash
# 1) 앱 종료 후 다시 기동
./gradlew bootRun
# 기동 로그:
# [KnowledgeLoader] RAG 시드 완료 — 신규 0건 / 스킵 7건 / 총 7건

# 2) PgVector에 몇 건 있는지 직접 확인
docker exec -it baedal-pgvector psql -U baedal -d baedal \
  -c "SELECT id, metadata->>'category' as category, metadata->>'title' as title FROM vector_store;"
```

> 🎯
**체크포인트**: 재기동 후 “신규 0건 / 스킵 7건”이 찍히면 중복 방지 로직이 정상 동작하는 것입니다. 만약 매번 적재되면 `filterExpression` 문법, metadata의 key 이름(`faqId` 대소문자) 등을 확인하세요.
>

---

## 4부. Memory + RAG의 협업과 환각 방지

### 4.1 Advisor 체인의 진가

Round 3 Memory와 Round 4 RAG가 **같이** 붙었을 때 비로소 의미있는 상담이 됩니다.

```
고객(1턴): "주문번호 2024-1234 어디쯤이에요?"
 봇   (1턴): (Memory 기록) (Tool: getDeliveryStatus) "역삼역 사거리 부근입니다"

고객(2턴): "아까 그 주문, 환불 돼요?"
   └─ [Memory Advisor] 이전 턴의 "2024-1234"를 프롬프트에 복원
   └─ [QA Advisor] 이 질문을 임베딩 → "환불" 관련 정책 문서 검색 → Context 주입
   └─ [LLM] 1234는 DELIVERING 상태. 배달 완료 전이므로 취소 경로 안내.
             단, 배달 완료 후 누락/오배송 사유라면 24시간 이내 접수 가능.
```

**관찰 포인트:** 이 한 턴에서 Memory가 해준 일(1234로 치환)과 RAG가 해준 일(환불 정책 문서 주입)은 **전혀 다른 일**이지만 같은 체인에서 자연스럽게 맞물립니다.

> 🎯
**핵심 메시지**: Advisor는 “필터 체인”이다. 각 Advisor는 한 가지 일만 잘하고, 체인이 그걸 조립한다. 이게 Spring AI의 핵심 철학이며, “기능을 더하려면 새 Advisor를 끼운다”로 확장한다.
>

### 4.2 환각(Hallucination) 방지 — 검색 결과가 없을 때

RAG의 달콤함 뒤에는 함정이 있습니다. **검색 결과가 빈약하거나 관련 없으면 LLM은 여전히 꾸며냅니다.** 그걸 막는 2중 방어:

### 방어 1: 유사도 임계값

```java
SearchRequest.builder()
    .topK(4)
    .similarityThreshold(0.5)   // 이 미만은 버림
    .build();
```

임계값이 낮으면 무관한 문서도 통과되어 LLM을 오도합니다. 너무 높으면 관련 문서도 버려집니다. 배달 도메인에서는 0.5~0.7 사이에서 실험이 필요합니다.

> ⚠️
**임계값은 임베딩 모델마다 다시 측정해야 합니다.** 유사도 점수의 분포는 임베딩 모델에 따라 달라집니다. `0.5`는 출발점일 뿐 “정답”이 아닙니다. `qwen3-embedding:0.6b`로 실제 도메인 질문을 몇 개 돌려 보고, “맞는 정책은 통과하고 도메인 밖 질문은 떨어지는” 경계를 직접 찾으세요(숙제 3단계). 모델을 바꾸면 이 값도 다시 잡아야 합니다.
>

### 방어 2: 시스템 프롬프트의 “모름 규칙”

```
[정책 인용 규칙]
- 환불, 취소, 배달 지연 보상, 쿠폰 관련 질문은 반드시 제공된 Context를 근거로만 답합니다.
- Context에서 답을 찾을 수 없으면 추측하지 말고 이렇게 답합니다:
  "해당 내용은 확인이 필요합니다. 상담원 연결로 도와드리겠습니다."
- 정책을 인용할 때는 원문의 수치/조건을 그대로 사용합니다. 임의로 반올림하지 않습니다.
```

이 두 개가 함께 있을 때 환각이 실질적으로 줄어듭니다. 둘 중 하나만으로는 부족합니다.

### 4.3 라이브 데모 — Fallback 관찰

```bash
# 데모 1: 정상 케이스 — 정책 원문이 답에 나와야 함
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: fallback-1" \
  -d '{"message":"배달 완료 후에도 환불 받을 수 있나요?"}'

# 데모 2: Memory + RAG 협업
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: fallback-2" \
  -d '{"message":"2024-1234 배달 상황 알려주세요"}'

curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: fallback-2" \
  -d '{"message":"아까 그 주문 환불 돼요?"}'

# 데모 3: 상담 범위 밖 — Fallback이 작동해야 함
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: fallback-3" \
  -d '{"message":"오늘 점심 뭐 먹을까요?"}'
# → 기대: "저는 주문/배달/환불 관련 상담을 도와드리고 있어요"
```

| 데모 | 기대 응답 | 무엇이 보장하는가 |
| --- | --- | --- |
| 1 | 원문 수치(“24시간 이내”, “사진 증빙”) 포함 | VectorStore에 해당 정책 적재 + QA Advisor |
| 2 | Memory가 1234를 이어붙이고 + 환불 정책 인용 | Advisor 체인의 협업 |
| 3 | “상담 범위가 아닙니다” 안내 | 유사도 임계값(무관 문서 탈락) + 시스템 프롬프트 Fallback 규칙 |

> 🎯
**체크포인트**: 데모 3에서 “비빔밥 추천드려요”처럼 LLM이 답해버리면 (1) `[정책 인용 규칙]`이 시스템 프롬프트에 들어갔는지, (2) `similarityThreshold`가 너무 낮지 않은지 확인하세요.
>

### 4.4 RetrievalAugmentationAdvisor — Advanced

Spring AI 1.0은 `QuestionAnswerAdvisor`보다 한 단계 유연한 `RetrievalAugmentationAdvisor`도 제공합니다. Query Transformer, Document Joiner 등을 주입해 검색 파이프라인을 커스터마이징할 수 있습니다.

수업에서는 `QuestionAnswerAdvisor`만 다룹니다. 심화는 숙제 선택 과제에서 시도합니다.

### 4.5 RAG가 실패하는 시나리오 모음

RAG는 만능이 아닙니다. 실패하는 대표 패턴:

| 실패 유형 | 원인 | 대응 |
| --- | --- | --- |
| **Top-K에 무관 문서 섞임** | 임계값 너무 낮음 | `similarityThreshold` 0.6~0.7로 |
| **정답 문서를 못 찾음** | 청크가 너무 커서 의미 뭉툭 | 청크 크기를 300~500으로 |
| **정답 조각이 청크 경계에 걸림** | 오버랩 부족 | 오버랩을 20~30%로 |
| **질문이 짧아 검색 품질 저하** | 질문 자체가 정보 부족 | Query Rewriter(Advanced) |
| **멀티 카테고리 혼동** | 모든 문서가 한 인덱스에 섞임 | `filterExpression`으로 카테고리 분할 |

### 4.6 임베딩 모델의 한계

- `qwen3-embedding:0.6b`는 100개 이상 언어를 지원하는 다국어 모델이라 **한국어 검색 품질이 좋은 편**입니다. 수업 LLM(`qwen2.5`)과 같은 Qwen 패밀리라 결도 맞습니다.
- 그래도 임베딩 모델은 만능이 아닙니다. 짧은 구어체 질문(“쿠폰 돼요?”)이나 오타가 섞인 질문은 정책 문서와의 유사도가 낮게 나와 임계값 아래로 떨어질 수 있습니다 — 이때는 `similarityThreshold`를 낮추거나 질문 재작성(4.4절 Advanced)을 검토합니다.
- 더 높은 품질이 필요하면 `qwen3-embedding:4b`(2.5GB, 메모리 부담↑)나 `bge-m3`(1024차원) 같은 더 큰 다국어 모델을 검토할 수 있습니다. 다만 모델이 커질수록 다운로드/메모리 비용이 늘어 LLM과의 동시 구동이 빠듯해집니다.
- 숙제에서 “동일 질문을 다른 임베딩 모델로 테스트”해 보면 모델별 검색 품질 차이가 크게 느껴집니다.

### 4.7 더 이야기하고 싶은 부분

- S3 Vector
    - https://news.hada.io/topic?id=22354

---

## 다음 라운드 예고 — Round 5: Guardrail과 에이전트 신뢰성

다음 시간에는 **“에이전트를 믿을 수 있게 만드는 법”** 을 다룹니다.

- 고객: “주문번호 없이 환불해주세요” → 입력 검증이 필요한 순간
- 고객: “사장님 연락처 알려줘” → 출력에서 거르거나 응답을 거부해야 하는 순간
- 고객: “나 너무 화나는데 사람이랑 얘기하고 싶어” → 감정 분석 기반 상담원 전환
- LLM이 엉뚱한 Tool을 호출하거나 RAG가 관련 없는 문서를 쏟아내는 순간 — **실패 처리 패턴**

다음 라운드에서는 지금까지 쌓은 Tool / Memory / RAG 위에 **Guardrail 레이어**를 얹습니다. 그게 프로덕션 에이전트를 만드는 마지막 블록입니다.

- (선행 학습 권장)
    - 스팸 / 프롬프트 인젝션 공격 사례 한 번 훑기 (검색어: “prompt injection examples”)
    - 배달의 실제 상담 정책 페이지에서 “민감 정보 처리” 부분 읽어보기
    - Round 3 `[대화 맥락 사용 규칙]` + Round 4 `[정책 인용 규칙]` 은 이미 “작은 Guardrail”이다. 이 둘을 다시 훑어보면서 “이것만으로 충분한가?”를 스스로 질문해 보세요.