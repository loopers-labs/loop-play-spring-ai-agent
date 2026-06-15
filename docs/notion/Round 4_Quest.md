# Round 4 Quests

> 🎯
이번 라운드 숙제는 **단계별 부분 제출이 가능**합니다. 막혀도 거기까지 제출하세요.
>

> 📝
이 숙제의 핵심은 RAG를 “켜는 것”이 아니라 **검색 품질 경계(청크 / Top-K / 임계값 / Fallback)를 설계하는 훈련**입니다.
AI로 코드를 생성해도 됩니다. 단, **왜 그 청크 크기·Top-K·임계값·Fallback 정책을 선택했는지**와 **그 경계가 무너지면 LLM이 어떻게 환각하는지**를 직접 관찰하고 기록하세요.
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

- 왜 RAG가 필요한가를 LLM의 학습 데이터 한계, 최신성, 도메인 특화 지식의 세 관점에서 설명할 수 있다
- RAG의 두 파이프라인 — 인덱싱 vs 검색 — 을 말로 설명할 수 있다
- `VectorStore` / `EmbeddingModel` / `TokenTextSplitter` / `QuestionAnswerAdvisor`의 역할을 구분할 수 있다
- 청킹의 크기/오버랩 트레이드오프를 설명하고, 검색 품질 변화를 관찰할 수 있다
- Memory Advisor와 QA Advisor가 같은 체인에서 어떻게 협업하는지 로그로 설명할 수 있다
- 검색 결과가 없을 때의 Fallback 전략을 유사도 임계값 + 시스템 프롬프트로 설계할 수 있다

---

## 사전 준비

- [ ]  **JDK 17 이상** — `java -version` 으로 확인
- [ ]  **Round 3 숙제가 완료된 상태** — Memory 3레이어 + `X-Session-Id` + Tool Calling이 동작해야 함 (스타터 코드에 Round 3 구현이 포함되어 있음)
- [ ]  **Docker / Docker Desktop** 실행 중 — PgVector 컨테이너용
- [ ]  **Ollama** 실행 중, 두 모델 다운로드 완료:

   ```bash
   ollama pull qwen2.5
   ollama pull qwen3-embedding:0.6b   # 1024차원, 약 640MB
   ollama list                    # 두 모델이 모두 보여야 함
   ```

- [ ]  Postman / `httpie` / `curl` (`jq` 권장), `psql`(PgVector 테이블 직접 조회용)
- [ ]  IntelliJ IDEA — DEBUG 로그에서 “프롬프트에 주입된 Context 블록”을 눈으로 확인

---

## 시작하기

```bash
# 1) starter-code를 본인 레포에 복사 (docker-compose.yml이 starter-code 루트에 포함됨)
git branch round4

# 2) Ollama 확인
ollama list  # qwen2.5 + qwen3-embedding:0.6b 둘 다 확인

# 3) PgVector 기동
docker compose -f docker-compose.yml up -d
docker ps | grep baedal-pgvector   # Up (healthy) 확인

# 4) 실행 — 현 상태에서는 RagConfig Bean이 null을 반환하므로
#    Spring Bean 생성 시 에러가 난다. 1단계 구현을 마친 후 정상 실행된다.
./gradlew bootRun

# 5) 1단계 TODO를 찾아 차례로 채운다
#    - RagConfig.java             (4개TODO: TOP_K + THRESHOLD + Splitter + QA Advisor)
#    - KnowledgeLoader.java       (2개TODO: Document 변환/적재 + 중복 방지)
#    - AssistantController.java   (1개TODO: ragAdvisor 체인 등록)
#    - SupportController.java     (1개TODO: 동일 패턴)
#    - BaedalPrompt.java          (1개TODO: [정책 인용 규칙] 섹션 직접 작성)

# 6) 첫 검증
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: smoke-test" \
  -d '{"message":"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"}'

# 7) PgVector 테이블 확인
docker exec -it baedal-pgvector psql -U baedal -d baedal \
  -c "SELECT count(*), metadata->>'category' FROM vector_store GROUP BY metadata->>'category';"
```

**RAG가 동작하지 않을 때 확인할 것:**

1. `ollama list`에 `qwen3-embedding:0.6b`가 있는가? (없으면 `ollama pull qwen3-embedding:0.6b`)
2. `docker ps`에서 `baedal-pgvector`가 Up 상태인가?
3. `KnowledgeLoader`의 기동 로그에 `신규 N건` 또는 `스킵 N건`이 찍혔는가? (0건이면 `knowledge/*.md` 파일이 classpath에 있는지 확인)
4. `RagConfig`의 두 Bean(`tokenTextSplitter`, `questionAnswerAdvisor`)이 null이 아닌 실제 객체를 반환하는가?
5. `AssistantController`에 `.defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)` 3개가 **순서대로** 등록되었는가?
6. 응답이 정책 원문을 포함하지 않으면 `similarityThreshold`가 너무 높지 않은지, 임베딩 모델 차원(1024)과 `application.yml`의 `dimensions: 1024`이 일치하는지 확인.

---

## 1단계: RAG 기본 구현 + 시나리오 5종 검증 (30점)

**목표**: PgVector + 임베딩 + `QuestionAnswerAdvisor`로 RAG 파이프라인을 만들고, 5종 시나리오로 검색·Fallback·Memory 협업을 검증한다.

### 구현

- [ ]  git branch round4 checkout
- [ ]  `RagConfig.java`의 `// TODO [1단계-A~D]` 4개를 채운다:
    - `TOP_K` 값 결정 (권장 4)
    - `SIMILARITY_THRESHOLD` 값 결정 (권장 0.5 — qwen3 임베딩 기준으로 도메인 질문 몇 개 돌려 보고 조정)
    - `TokenTextSplitter` Bean (청크 800 / min 350)
    - `QuestionAnswerAdvisor` Bean (`order(20)`)
- [ ]  `KnowledgeLoader.java`의 `// TODO [1단계-E, F]` 2개를 채운다:
    - FaqDocument → `Document` 변환 + `tokenTextSplitter.apply()` + `vectorStore.add()`
    - `alreadyLoaded(faqId)` — `filterExpression("faqId == '...'")` 중복 방지
- [ ]  `AssistantController.java`와 `SupportController.java`의 `// TODO [1단계-G, H]`에서 Advisor 체인에 `ragAdvisor`를 추가한다 (순서: `memoryAdvisor → ragAdvisor → performanceAdvisor`)
- [ ]  `BaedalPrompt.java`의 `// TODO [1단계-J]`에서 `[정책 인용 규칙]` 섹션을 직접 작성 (Fallback 문구 / 원문 수치 유지 / 상담 범위 밖 대응 / 복수 정책 우선순위)
- [ ]  기동 로그에서 `RAG 시드 완료 — 신규 7건 / 스킵 0건`을 확인하고, 재기동 시 `신규 0건 / 스킵 7건`이 찍히는지 확인

### 검증 — 시나리오 5종

아래 시나리오 5종을 실행하고, **응답 본문**과 **DEBUG 로그의 `Context:` 블록 발췌**와 **`vector_store` 테이블의 row 수/카테고리 분포**를 README에 붙여라.

| # | 시나리오 | 기대 |
| --- | --- | --- |
| 1 | `"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"` | `delivery-delay/weather-delay`가 Top-K. 응답에 “기상 특보”, “예상 시간 + 30분”, “쿠폰” 같은 원문 수치 포함 |
| 2 | `"결제 후 바로 취소하면 환불되나요?"` | `cancel/cancel-policy`가 Top. “조리 시작 전 / 후” 구분 + 카드 승인 취소 vs 환불 프로세스 언급 |
| 3 | `"쿠폰 중복 사용되나요?"` | `coupon/coupon-faq`가 Top. “중복 적용 불가 / 최소 주문 금액 / 카테고리” 같은 원문 |
| 4 | `"사장님 전화번호 알려주세요"` | `account/privacy` 정책 히트 + `[금지]` 규칙에 따라 **거절**. 전화번호 노출 없음 |
| 5 | Memory+RAG 협업: `A: "2024-1234 배달 어디쯤?"` → `A: "아까 그 주문 환불 돼요?"` | 2회차에서 Memory가 `2024-1234` 유지 + RAG가 환불 정책 검색 + 현재 주문 상태에 맞는 답변 |

```bash
# 시나리오 1 예시
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: rag-1" \
  -d '{"message":"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"}'

# 시나리오 5 (2턴)
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: memo-rag" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤이에요?"}'

curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" -H "X-Session-Id: memo-rag" \
  -d '{"message":"아까 그 주문 환불 돼요?"}'

# 세션 Memory 확인 (Round 3 엔드포인트)
curl -s http://localhost:8080/api/v1/session/memo-rag/messages | jq

# PgVector 직접 조회
docker exec -it baedal-pgvector psql -U baedal -d baedal \
  -c "SELECT count(*), metadata->>'category' FROM vector_store GROUP BY metadata->>'category';"
```

### 설계 결정 문서 (README에 작성)

- [ ]  **왜 청크 크기 800 / min 350인가?** 배달 정책 문서가 조항 단위로 이미 쪼개져 있다는 사실을 근거로 설명. “블로그 글 / 장문 PDF” 같은 다른 도메인이라면 어떻게 바꿀 것인가?
- [ ]  **왜 Top-K=4인가?** 정책 문서가 7건인 상황에서 1 / 4 / 10 중 4를 선택한 근거를 설명
- [ ]  **왜 `QuestionAnswerAdvisor.order(20)`인가?** `memoryAdvisor(10) → ragAdvisor(20) → performanceAdvisor(100)` 순서가 필요한 이유를 **시나리오 5를 예시로** 설명
- [ ]  **`similarityThreshold=0.5`의 근거.** 너무 낮으면 / 너무 높으면 각각 어떤 문제가 생기는가? (실험은 3단계 과제)

> 📝 **체크리스트**
- [ ] `./gradlew bootRun`으로 프로젝트가 정상 실행되는가? (`KnowledgeLoader` 로그에 `신규 7건`)
- [ ] 시나리오 5종의 응답 본문 + 프롬프트에 주입된 `Context:` 블록 발췌가 모두 README에 있는가?
- [ ] 시나리오 4에서 `[금지]` 규칙에 따라 전화번호가 **노출되지 않았음**을 증명했는가?
- [ ] 시나리오 5에서 “아까 그 주문”이 `2024-1234`로 복원되어 환불 정책 검색에 쓰였음을 `/api/v1/session/memo-rag/messages`로 증명했는가?
- [ ] 재기동 후 `스킵 7건`이 찍혀 중복 방지가 동작함을 증명했는가?
- [ ] 청크 크기 / Top-K / Advisor order / 임계값 4가지 설계 결정의 “왜?” 답이 README에 있는가?
>

---

## 2단계: 청킹 전략 실험 + 실패 관찰 (25점)

**목표**: `TokenTextSplitter`를 바꿔 가며 같은 질문 시퀀스를 돌리고, 검색 품질/입력 토큰/청크 수를 정량 비교한다.

### 구현

1. `RagConfig`의 `tokenTextSplitter()`를 **3가지 값**으로 번갈아 바꾸며 실험하라. **각 실험 전에 PgVector를 반드시 비워야 한다**:

    ```bash
    docker exec -it baedal-pgvector psql -U baedal -d baedal \
      -c "TRUNCATE TABLE vector_store;"
    ```

   그리고 앱 재기동 후 `KnowledgeLoader`가 새 설정으로 재적재하게 한다.

   | 실험 | chunkSize | minChunkSizeChars | 목적 |
       | --- | --- | --- | --- |
   | A | `800` (기준) | `350` | 정상 케이스 |
   | B | `100` (극단적으로 작게) | `40` | **문맥 조각남** 관찰 |
   | C | `2000` (크게) | `800` | 유사도 뭉툭 + 토큰 낭비 관찰 |
2. 각 실험마다 **동일한 5개 질문 시퀀스**를 실행한다:

    ```
    1) "비 오는 날 배달이 늦으면 보상 받을 수 있나요?"
    2) "결제 후 바로 취소하면 환불되나요?"
    3) "쿠폰 중복 사용되나요?"
    4) "배달 완료 후에도 환불 받을 수 있나요?"
    5) "오늘 점심 뭐 먹을까요?"        ← 도메인 밖 (Fallback 기대)
    ```


### 정량 비교 표 (README에 작성)

| 실험 | chunkSize | vector_store row 수 | 평균 입력 토큰 (5턴) | 답변 품질 (만족/애매/불만족) |
| --- | --- | --- | --- | --- |
| A | 800 | ? | ? | ? |
| B | 100 | ? | ? | ? |
| C | 2000 | ? | ? | ? |
- `row 수`는 `SELECT count(*) FROM vector_store;` 값
- `평균 입력 토큰`은 `PerformanceLoggingAdvisor`의 로그(`입력 토큰: YY`)를 5턴 평균
- 답변 품질 기준(만족/애매/불만족)을 자기 기준으로 정의하여 README에 명시

### 실패 관찰 — 청크가 너무 작을 때(100) / 클 때(2000) (README에 작성)

> ⚠️
형식적인 “안 됐어요”가 아니라, **시스템이 어떻게 망가지는지** 출력 자체를 그대로 기록하는 것이 핵심입니다.
>
- [ ]  실험 B(chunkSize=100)에서 **문맥이 조각난 사례**를 1개 이상 캡처하라:
    - 예: “기상 특보 시 예상 시간 +” 에서 숫자가 잘린 청크만 Top-K에 올라오고, 정작 “30분” 숫자가 있는 청크가 임계값 아래로 떨어져 LLM이 “약간의 지연이 있을 수 있습니다” 같은 **뭉개진 답**을 하는 경우
    - DEBUG 로그의 `Context:` 블록 전문과 LLM의 응답을 함께 붙여라
- [ ]  실험 C(chunkSize=2000)에서 `row 수`가 A보다 적어지는 것을 확인하고, 유사도 점수가 **전반적으로 낮아지는** 경향을 관찰하라. 이유를 설명하라 (힌트: 청크 하나에 여러 주제가 섞이면 질문 벡터와의 유사도가 뭉툭해진다)

### 실패 관찰 — Fallback 없는 환각 (README에 작성)

1. `BaedalPrompt`의 `[정책 인용 규칙]` **전체를 임시로 주석 처리**한다
2. 시나리오 5의 도메인 밖 질문(`"오늘 점심 뭐 먹을까요?"`)을 다시 보내라
3. LLM이 어떻게 답하는지 캡처하라. 대부분 “비빔밥을 추천드려요” 같은 **상담 범위를 벗어난 답**을 한다
4. 규칙을 복원한 후 다시 보내 결과를 비교하라

### 설계 결정 문서 (README에 작성)

- [ ]  배달 정책 도메인에서 가장 적합한 청크 크기는 얼마인가? A/B/C 실험 결과를 근거로 정하라
- [ ]  청크 오버랩(`TokenTextSplitter`의 내부 기본값)을 0으로 바꾸면 어떤 문제가 생기는가? 왜 오버랩이 필요한가?
- [ ]  만약 문서가 **“사용자 리뷰 10만 건”** 이라면 청크 전략이 어떻게 달라져야 하는가? 청크 크기 / 중복 방지 / 재인덱싱 주기 관점에서 답하라
- [ ]  **“similarityThreshold만으로 환각을 막을 수 있는가?”** — `[정책 인용 규칙]` 주석 처리 실험 결과를 근거로 답하라

> 📝 **체크리스트**
- [ ] 3가지 청크 크기의 `row 수 / 평균 입력 토큰 / 답변 품질` 비교 표가 있는가?
- [ ] 실험 B에서 문맥 조각난 `Context:` 블록 캡처 + LLM 응답 캡처가 있는가?
- [ ] `[정책 인용 규칙]` 제거 시 LLM이 환각하는 사례가 캡처되어 있는가?
- [ ] 오버랩 필요성 + 다른 도메인(리뷰)에 대한 청크 감각이 문서화되어 있는가?
>

---

## 3단계: Memory + RAG 동시 적용 + Advisor 순서 실험 (20점)

**목표**: Memory와 RAG가 같은 체인 위에서 각각 무슨 일을 하는지 관찰하고, Advisor 순서를 일부러 뒤바꿔 무엇이 깨지는지 본다.

### 구현

1. **2턴 대화**를 실행하고 Memory / RAG가 각각 어떤 일을 했는지 관찰한다:

    ```bash
    curl -s -X POST http://localhost:8080/api/v1/assistant \
      -H "Content-Type: application/json" -H "X-Session-Id: chain-obs" \
      -d '{"message":"주문번호 2024-1234 배달 어디?"}'
    
    curl -s -X POST http://localhost:8080/api/v1/assistant \
      -H "Content-Type: application/json" -H "X-Session-Id: chain-obs" \
      -d '{"message":"아까 그 주문 환불 돼요?"}'
    ```

   각 턴마다 다음을 캡처하라:

    - 2턴의 DEBUG 로그에서 **Memory가 주입한 이전 USER/ASSISTANT 메시지**
    - 2턴의 DEBUG 로그에서 **QA Advisor가 주입한 Context 블록**(환불 정책 원문)
    - LLM 최종 응답
2. **Advisor 순서를 의도적으로 뒤바꾼다.** `RagConfig`의 `questionAnswerAdvisor` Bean에서 `order(20)`을 `order(5)`로 바꿔 **RAG가 Memory보다 먼저 실행**되게 한다:

    ```java
    return QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .order(5)   // 실험: Memory(10)보다 먼저 — 일부러 고장내기
            .build();
    ```

3. 위 2턴 대화를 다시 실행하고 차이를 관찰하라:
    - 2턴의 Context 블록에 들어간 정책 문서가 **무엇에 대한 것인가**? (힌트: “그 주문”이 아직 `2024-1234`로 복원되기 전이라 “아까 그 주문 환불 돼요?” 자체로 검색하게 된다)
    - LLM 최종 응답이 어떻게 달라졌는가? 정확한 정책이 인용되는가?

### 관찰 기록 표 (README)

| 관찰 포인트 | `memory(10) → rag(20)` (정상) | `rag(5) → memory(10)` (고장) |
| --- | --- | --- |
| 2턴 Context에 들어간 정책 카테고리 | ? | ? |
| Context 정책이 현재 주문(1234)과 관련 있는가 | ? | ? |
| LLM 응답의 정확도 (원문 수치 포함 여부) | ? | ? |

### 설계 결정 문서 (README에 작성)

- [ ]  **왜 Memory Advisor가 RAG보다 먼저 실행되어야 하는가?** “아까 그 주문”을 예시로 한 설명을 쓰되, **프롬프트 조립 순서** 관점에서 말하라 (Memory가 먼저 이전 턴 메시지를 프롬프트에 넣으면 RAG는 그 “복원된 질문”을 임베딩한다)
- [ ]  **반대 순서가 더 나은 상황이 존재하는가?** 예를 들어 “Memory에는 개인 정보가 들어있어 임베딩하면 안 되는 경우”를 고려해 보라 (Round 5 Guardrail 예고)
- [ ]  실험 후 원래 순서(`order(20)`)로 복원하라

> 📝 **체크리스트**
- [ ] 2턴 대화의 Memory 주입 / RAG 주입 / LLM 응답 3요소가 모두 캡처되어 있는가?
- [ ] Advisor 순서를 뒤바꾼 실험의 “무엇이 깨졌는지”가 표로 정리되어 있는가?
- [ ] “왜 Memory가 먼저인가”에 대한 설명이 프롬프트 조립 순서 관점에서 작성되어 있는가?
>

---

## 4단계: Observability + AI 코드 리뷰 (15점)

**목표**: RAG가 주입하는 토큰 비용을 로그로 직접 관찰하고, AI가 만든 RAG 코드의 프로덕션 결함을 검토한다.

### 구현 — PerformanceLoggingAdvisor로 RAG 주입의 토큰 비용 관찰

1. 다음 세 조건으로 **같은 질문**을 보내 입력 토큰을 비교하라:

    ```
    질문: "배달 완료 후에도 환불 받을 수 있나요?"
    ```

   | 조건 | Advisor 체인 | 기대 |
       | --- | --- | --- |
   | (a) Memory 없음 + RAG 없음 | `performanceAdvisor`만 | 가장 작은 입력 토큰 |
   | (b) Memory만 | `memoryAdvisor, performanceAdvisor` | (a)와 유사 (빈 Memory) |
   | (c) Memory + RAG | `memoryAdvisor, ragAdvisor, performanceAdvisor` | 가장 큰 입력 토큰 |

   (a), (b)는 일시적으로 `defaultAdvisors(...)` 목록을 수정하여 관찰한다. 관찰 후 (c)로 복원.

2. **정량 비교 표**를 README에 작성하라:


    | 조건 | 입력 토큰 | 출력 토큰 | 응답 시간(ms) | 비고 |
    | --- | --- | --- | --- | --- |
    | (a) Memory 없음 + RAG 없음 | ? | ? | ? |  |
    | (b) Memory만 | ? | ? | ? |  |
    | (c) Memory + RAG | ? | ? | ? | RAG Context 블록 포함 |
    
    (c)가 (a)보다 입력 토큰이 **얼마나 증가**했는가? 이 증가의 실체(Context에 삽입된 정책 원문)를 DEBUG 로그에서 캡처하여 README에 붙여라.

3. **Context 블록 원문 캡처:** 조건 (c)의 DEBUG 로그에서 LLM에 전달된 최종 프롬프트를 찾아 `Context:` 섹션 전체를 캡처하라. 어느 FAQ 문서(`refund-basic`, `refund-after-delivered` 등)가 어떻게 삽입되었는지 확인하라.

### AI 코드 리뷰 — 프로덕션 결함 찾기 (README에 작성)

1. AI(ChatGPT, Claude, Cursor 등)에게 아래 프롬프트로 코드를 요청하라:
> `"Spring AI 1.0으로 RAG 기반 FAQ 챗봇을 만들어줘. PgVector와 OpenAI 임베딩을 써."`
2. 받은 코드에서 **프로덕션에 올리면 안 되는 결함 3개**를 찾아 기록하라 (아래 힌트 중 3개 이상 해당):
    - **청크 무분할**: `TokenTextSplitter` 미사용 → 유사도 뭉툭 / 토큰 낭비
    - **Top-K 무설정**: `similaritySearch` 기본값 → 프롬프트 폭증
    - **유사도 임계값 없음**: 관련 없는 문서도 Top-K에 포함 → 환각
    - **출처(metadata) 표시 없음**: “어느 정책 문서를 근거로 답했는지” 추적 불가
    - **임베딩 모델 차원 불일치**: 인덱싱은 `text-embedding-3-small`(1536), 검색은 `qwen3-embedding:0.6b`(1024)로 섞어 써서 벡터 공간이 안 맞음 → 조용히 쓰레기 결과
    - **중복 적재 방지 없음**: 앱 재기동마다 같은 문서가 두 배씩 쌓임
    - **Fallback 미설계**: 검색 0건이어도 LLM이 상상으로 답변
    - **개인정보 임베딩**: 마스킹 없이 전화번호/주소가 포함된 문서를 임베딩 → GDPR 위험
    - **Advisor 체인 순서**: Memory/RAG 순서 고려 없음
    - **initialize-schema=true를 프로덕션에 두기**: 운영 시 마이그레이션 도구로 분리해야 함
3. 각 결함마다 **이번 수업에서 배운 방식으로 어떻게 고칠지** 개선 방안을 작성하라
   (예: `"TokenTextSplitter(800, 350)로 청킹 / similarityThreshold(0.5) 적용 / alreadyLoaded() 중복 방지 / metadata.title을 응답에 함께 로깅"`)
4. AI가 생성한 원본 코드와 본인의 개선 코드를 함께 README에 첨부하라

> 📝 **체크리스트**
- [ ] (a)/(b)/(c) 3조건 입력 토큰 비교 표가 있는가?
- [ ] (c)에서 실제 프롬프트에 주입된 `Context:` 블록 전문이 캡처되어 있는가?
- [ ] AI 생성 코드의 원본이 README에 첨부되어 있는가?
- [ ] 결함 3개 + 각각의 개선 방안(수업 내용과 연결)이 구체적으로 쓰여 있는가?
>

---

## 공통: 학습 기록 (10점)

README 하단에 다음 세 단락을 작성하라:

- [ ]  **“내가 배운 것”** — Round 4에서 새롭게 알게 된 점 (RAG 2파이프라인 / 임베딩 직관 / 청킹 트레이드오프 / Advisor 체인 협업 / Fallback 2중 방어 중 **본인이 직접 체감한 것** 위주로 한 단락 이상)
- [ ]  **“의문점”** — 아직 해결되지 않은 궁금증
  (예: “한국어 임베딩 품질을 어떻게 측정할 수 있을까?” “검색 결과의 출처를 응답에 어떻게 자동으로 노출할까?” “정책 문서가 자주 바뀌는 경우 재인덱싱을 언제/어떻게 트리거할까?”)
- [ ]  **“Round 5(Guardrail)에 시도하고 싶은 것”** — RAG와 Guardrail을 연결할 아이디어
  (예: “RAG 검색 결과가 0건일 때 바로 ‘상담원 연결’ Tool을 호출하는 Guardrail Advisor를 만들고 싶다” / “사장님 전화번호 질문처럼 `[금지]` 규칙에 해당하는 질문은 임베딩/검색 자체를 건너뛰는 입력 필터가 있으면 좋겠다”)

---

## 평가 기준 요약

| 단계 | 항목 | 배점 |
| --- | --- | --- |
| 1단계 | RAG 기본 구현 + 시나리오 5종 검증 + 설계 결정 문서 | 30 |
| 2단계 | 청킹 전략 실험 3조건 + 문맥 조각남 관찰 + Fallback 없는 환각 관찰 | 25 |
| 3단계 | Memory+RAG 협업 2턴 관찰 + Advisor 순서 뒤바꾸기 실험 | 20 |
| 4단계 | RAG 주입 토큰 관찰 + Context 블록 캡처 + AI 코드 리뷰 | 15 |
| 공통 | 학습 기록 (“내가 배운 것 / 의문점 / Round 5 아이디어”) | 10 |
|  | **합계** | **100** |

> 💡 **단계별로 제출 가능합니다.** 1단계만 해도 30점, 2단계까지 하면 55점입니다.
코드보다 **청크/임계값 선택 근거와 실패 관찰 기록**의 품질이 평가에서 더 큰 비중을 차지합니다.
>

---

## 제출 가이드

1. 본인 GitHub 레포에 push
2. README.md에 다음을 모두 포함:
    - 1단계 시나리오 5종 응답 + `Context:` 블록 발췌 + `vector_store` 테이블 조회 결과 + 설계 결정 문서 4가지
    - 2단계 청크 크기 3조건 비교 표 + 문맥 조각남 캡처 + `[정책 인용 규칙]` 제거 시 환각 캡처
    - 3단계 Memory+RAG 2턴 캡처 + Advisor 순서 뒤바꿈 관찰 표 + “왜 Memory가 먼저인가”
    - 4단계 (a)/(b)/(c) 토큰 비교 + Context 블록 전문 + AI 코드 리뷰 (원본 + 개선 코드)
    - 공통: “내가 배운 것 / 의문점 / Round 5 아이디어”
3. 다음 라운드 첫 수업 전까지 PR 또는 레포 링크 제출

### PR/제출물 체크

- [ ]  제목: `[Round 4] {본인 이름} - {몇 단계까지 완료}`
- [ ]  본문에 어디까지 완료했고 어디서 막혔는지 명시
- [ ]  API Key 등 민감 정보가 커밋에 포함되지 않았는지 확인
- [ ]  `./gradlew build` 로 컴파일 에러 없는지 마지막으로 확인
- [ ]  **변경 파일 20개 이하** — 넘으면 `.gitignore`에 `build/`, `.gradle/`, `.class`, `.idea/`, `.iml` 추가

### Merge 조건

- **페어 리뷰어 2명의 Approve** 가 필요합니다
- Approve 기준은 3축: 설계 결정의 근거 / 실패 관찰의 구체성 / 다음 라운드 연결
- **Round 4에서 리뷰어가 특히 보는 3가지**: (1) 청크 크기 A/B/C 정량 비교 + 문맥 조각남 캡처 (2) `[정책 인용 규칙]` 제거 시 환각이 실제로 일어나는 증거 (3) Advisor 순서 뒤바꿈 실험의 Context 차이
- 페어 리뷰어로 배정되면 **48시간 내 첫 코멘트**, 다음 라운드 첫 수업 24시간 전까지 결론