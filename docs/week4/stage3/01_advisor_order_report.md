# Advisor 순서 고장내기 리포트 — Memory와 RAG는 같은 체인 위에서 각각 무슨 일을 하는가

## 1. 실험 내용

- **무엇을**: 동일한 2턴 대화를 **정상 순서**(`MessageChatMemoryAdvisor` order=10 → `QuestionAnswerAdvisor` order=20)와 **고장 순서**(RAG `order(5)` → Memory `order(10)`)에서 각 1회씩 보내, Memory가 주입한 이전 대화·RAG가 주입한 Context 블록·LLM 최종 응답을 server.log에서 대조한다.
  - 턴1: `"주문번호 2024-1234 배달 어디?"` (주문번호 명시 → Memory 적재)
  - 턴2: `"아까 그 주문 환불 돼요?"` (대명사 → Memory가 1234를 복원해야 함)
- **왜**: Memory(이전 대화 이력)와 RAG(정책 검색)가 한 Advisor 체인 위에서 각각 무슨 일을 하는지 관찰하고, 순서를 일부러 뒤바꿔 **무엇이 깨지는지** 본다.
- **어떻게**:
  - 엔드포인트 `POST /api/v1/assistant`, session `chain-obs` 고정 → 2턴 멀티턴. Advisor 체인 = Memory(10) → RAG(20) → Performance(100).
  - order 변경은 `RagConfig.java:121` `.order(20)` → `.order(5)` 한 줄. 실행기 `docs/verification_memory/run_memory_scenarios.sh`(실행마다 서버 새로 기동/종료 → ChatMemory 초기화).
  - 절차 상세: [00_step_guide.md](advisor_order/00_step_guide.md). cases: [cases.json](advisor_order/cases.json). 모델 `qwen2.5:14b`, temperature 0.3.
- **제어 변수**: 두 런 모두 같은 2턴·같은 모델·같은 threshold(0.5)·같은 Top-K(4). **유일하게 다른 것은 RAG Advisor의 `order` 값**이다.

## 2. 관찰 기록 표 (숙제 요구)

| 관찰 포인트 | `memory(10) → rag(20)` (정상) | `rag(5) → memory(10)` (고장) |
| --- | --- | --- |
| 2턴 Context에 들어간 정책 카테고리 | refund(환불 기본 정책) + cancel(주문 취소 정책) | refund + cancel — **정상과 동일** |
| Context 정책이 현재 주문(1234)과 관련 있는가 | O — 환불 질문에 환불·취소 정책이 적합 | O — Context 자체는 정상과 동일하게 적합 |
| LLM 응답의 정확도 (원문 수치/상태 반영) | O — `getOrderDetail(2024-1234)` 재조회 → "조리 시작 후라 자동 환불 불가, 누락/오배송 시 증빙" 상태 기반 안내 | X — Tool **미호출**, 주문 상태를 **사용자에게 되물음**, 정책 안내 못 함 |

> 힌트가 예고한 "2턴 Context의 정책 카테고리가 갈린다"는 **재현되지 않았다.** 두 런의 Context는 같았다(아래 5-1). 실제로 깨진 곳은 **Memory에 저장된 이전 대화**와 **Tool 호출**이었다(5-2, 5-3). 왜 힌트와 달랐는지는 §3에서, "그 주문"을 1234로 복원해 검색하게 하려면 어떻게 해야 하는지는 §4에서 설명한다.

## 3. 왜 Context는 안 갈렸나 — QuestionAnswerAdvisor의 검색 입력

- 힌트의 전제는 "정상 순서에서는 Memory가 '그 주문'을 1234로 복원한 질문으로 RAG가 검색한다"였다. 그러나 **`QuestionAnswerAdvisor`는 그 턴의 user 메시지 원문**(`"아까 그 주문 환불 돼요?"`)**으로 검색**한다 — Memory는 이전 대화를 **별도 메시지로 추가**할 뿐, 현재 user 질문 문자열을 다시 쓰지 않는다.
- 그래서 검색 입력은 두 순서 모두 `"아까 그 주문 환불 돼요?"`로 **동일**하고, 문장에 든 **"환불" 키워드**가 refund/cancel 정책을 끌어온다. order를 바꿔도 Context가 그대로인 이유다.
- 즉 "Memory가 RAG의 검색어를 좋게 만든다"는 모델은 이 구현에선 성립하지 않는다. order가 실제로 가르는 것은 검색 품질이 아니라 **RAG 주입물이 Memory를 오염시키는지 여부**다(아래).

## 4. 어떻게 하면 "그 주문"을 2024-1234로 복원해 검색하게 할 수 있나

§3의 핵심은 **검색 입력이 그 턴 user 원문**이라는 점이다. 따라서 "그 주문"을 1234로 바꾸려면 **검색 전에 질문을 다시 쓰는(query rewriting) 단계**를 끼워 넣어야 한다. `QuestionAnswerAdvisor`(이 프로젝트가 쓰는 `spring-ai-advisors-vector-store`)에는 그 훅이 없으므로, 두 가지 방향이 있다.

### 4-1. 방향 A — Modular RAG의 Query Transformer (정석)

- Spring AI의 **modular RAG**(`spring-ai-rag` 모듈)는 검색 전에 질문을 변형하는 `QueryTransformer`를 제공한다. `QuestionAnswerAdvisor`를 `RetrievalAugmentationAdvisor`로 교체하고, 대화 이력 + 현재 질문을 **독립형 질문으로 압축**하는 `CompressionQueryTransformer`(또는 `RewriteQueryTransformer`)를 끼운다.
- 이 transformer는 LLM을 한 번 호출해 `이력["…2024-1234 배달 어디?"] + "아까 그 주문 환불 돼요?"` → **`"2024-1234 주문 환불 가능 여부"`** 같은 독립형 질문을 만든 뒤, 그 문장으로 벡터 검색을 한다. 비로소 "그 주문"이 1234로 복원된 채 검색된다.
- **전제: 이력이 transformer에 보여야 한다.** `Query`의 history는 프롬프트 메시지에서 채워지므로 **Memory가 먼저 실행**돼 이전 대화를 붙여야 한다 — 즉 retrieval advisor가 Memory보다 **뒤(order 큰 값)** 여야 한다. (이 실험에서 깨뜨린 정상 순서와 같은 방향. 단, 이번엔 "검색어 복원" 때문에 order가 진짜로 의미를 갖게 된다.)
- 비용: 검색마다 **LLM 호출 1회 추가**(지연·토큰↑), 압축 결과가 비결정적일 수 있음. 의존성 `org.springframework.ai:spring-ai-rag` 추가 필요(현재 클래스패스에 없음).

```java
// 스케치 — QuestionAnswerAdvisor 대신 RetrievalAugmentationAdvisor + CompressionQueryTransformer
// build.gradle: implementation 'org.springframework.ai:spring-ai-rag'
var ragAdvisor = RetrievalAugmentationAdvisor.builder()
        .queryTransformers(CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)   // 압축용 LLM 호출
                .build())
        .documentRetriever(VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.5)
                .topK(4)
                .build())
        .order(20)   // Memory(10) 뒤 — 이력이 채워진 뒤 압축·검색
        .build();
```

### 4-2. 방향 B — 주문번호만 복원하는 경량 전처리 Advisor (LLM 호출 없음)

- 대명사 전체를 일반화하지 않고 **이 도메인의 주문번호(`\d{4}-\d{4}`)만** 복원하면 충분하다면, Memory와 RAG **사이**(order 10 < n < 20)에 작은 커스텀 Advisor를 둔다.
- 그 Advisor가 `ChatMemory`(현재 conversationId의 이력)에서 **가장 최근 주문번호를 정규식으로 뽑아**, "아까/그/방금 그 주문" 같은 지시어가 있으면 검색에 쓸 질문 앞에 `2024-1234`를 붙인다(예: `"2024-1234 아까 그 주문 환불 돼요?"`). 그 뒤 RAG가 그 문장으로 검색한다.
- 장점: **추가 LLM 호출 없음**, 결정적. 단점: 주문번호 형태에만 동작(메뉴명·가게명 등 다른 대명사는 못 잡음), 정규식·지시어 규칙을 직접 관리해야 함.

### 4-3. 어느 쪽을 고를까

| 기준 | A. Query Transformer | B. 경량 전처리 Advisor |
| --- | --- | --- |
| 복원 범위 | 모든 맥락(메뉴·가게·기간 등) | 주문번호(`\d{4}-\d{4}`)만 |
| 추가 LLM 호출 | O (검색마다 1회) | X |
| 결정성 | 비결정적(LLM) | 결정적(정규식) |
| 구현 비용 | 의존성 추가 + advisor 교체 | 커스텀 advisor 1개 |
| Memory 순서 의존 | 있음(Memory 먼저여야 이력 확보) | 있음(Memory 먼저여야 주문번호 확보) |

- 공통점: **둘 다 Memory가 RAG보다 먼저** 실행돼야 한다(복원에 쓸 이력/주문번호를 Memory가 먼저 채워야 하므로). 즉 이 기능을 붙이면 §3에서 본 "order는 검색 품질엔 영향 없다"가 **뒤집혀, order가 검색 품질을 직접 가르게 된다.** 현재 `QuestionAnswerAdvisor`는 query rewriting이 없어서 order가 검색에 무관했을 뿐이다.

## 5. 실제로 깨진 것

### 5-1. 두 런의 2턴 Context는 동일 (검색 단계)

- 두 런 모두 2턴 user 메시지에 동일한 Context가 붙었다: `# 환불 기본 정책`(최대 7영업일·60분·24시간) + `# 주문 취소 정책`(CREATED/ACCEPTED/COOKING/DELIVERING). 1턴(배달 위치)은 두 런 모두 Context가 **빈 채로** 붙었다(threshold 0.5에서 전부 탈락).
- 산출물: [normal_server.log](advisor_order/responses/normal_server.log) · [swapped_server.log](advisor_order/responses/swapped_server.log).

### 5-2. 고장 순서는 Memory에 RAG의 Context 보일러플레이트를 저장했다 (구조적·결정적 차이)

2턴 프롬프트에 Memory가 주입한 **이전(1턴) user 메시지**가 두 런에서 달랐다:

| 런 | 2턴에 주입된 "이전 user 메시지" (server.log) |
| --- | --- |
| 정상 `memory(10)→rag(20)` | `"주문번호 2024-1234 배달 어디?"` — **원문 그대로** |
| 고장 `rag(5)→memory(10)` | `"주문번호 2024-1234 배달 어디?\n\nContext information is below ... ---------\n---------\n\nGiven the context ... If the answer is not in the context, inform the user that you can't answer the question."` — **RAG 보일러플레이트가 섞여 저장됨** |

- 메커니즘: RAG가 Memory보다 **먼저**(order 5 < 10) 돌면, RAG가 user 메시지에 `Context information is below ...` 스캐폴딩을 **덧붙인 뒤** Memory가 그 메시지를 history에 저장한다. 1턴 검색 결과가 0건이라 **빈 Context 스캐폴딩**이 통째로 대화 기억에 들어갔다.
- 정상 순서에서는 Memory가 RAG augmentation **전의 원문**을 저장하므로 기억이 깨끗하다.
- 부작용: 2턴 입력 토큰이 4050 → 4086으로 늘고(보일러플레이트만큼), 다음 턴마다 누적 오염된다.

### 5-3. 고장 순서는 Tool을 호출하지 못했다 (응답 단계)

| 축 | 정상 `memory(10)→rag(20)` | 고장 `rag(5)→memory(10)` |
| --- | --- | --- |
| 2턴 LLM 호출 | 2회 — #1 `getOrderDetail` tool_call(in=4050/out=108) → #2 최종 답(in=8351/out=226) | 1회 — tool 미호출(in=4086/out=60) |
| `getOrderDetail(2024-1234)` | O 호출됨 | X 호출 안 됨 |
| 2턴 최종 응답 | "주문 상태는 배달 중(DELIVERING)입니다. **조리가 이미 시작**된 상태이므로 자동 환불은 불가능합니다. … 음식 누락이나 오배송이면 **증빙 사진**을 제공하여 처리…" | "**먼저 주문 상태를 확인해 보겠습니다.** 2024-1234번 주문의 현재 상태는 **어떻게 되나요?** 환불 여부는 주문 상태와 사유에 따라…" |

- 정상은 주문 상태를 직접 재조회해 상태 기반으로 환불 가부를 안내했다. 고장은 Tool을 부르는 대신 `"먼저 ... 확인해 보겠습니다"`라는 **진행 서술**을 내고 주문 상태를 **사용자에게 되물었다** — 시스템 프롬프트가 명시적으로 금지한 패턴이다.

**왜 Tool을 못 불렀나.** 두 런의 **2턴 첫 LLM 호출**을 server.log에서 대조하면, 고장 순서가 정상보다 **deferral(되묻기) 쪽으로 기울 두 가지 차이**가 보인다.

| 첫 호출 차이 | 정상 `memory(10)→rag(20)` | 고장 `rag(5)→memory(10)` |
| --- | --- | --- |
| QA 템플릿 지시문 `"If the answer is not in the context, ... can't answer"` 등장 횟수 | 1회 (현재 턴에만) | **2회** — 5-2의 오염으로 1턴 user에도 박혀 **중복**됨 |
| 이전(1턴) user 메시지 | 원문 `"…배달 어디?"` | RAG 보일러플레이트가 붙은 채 |
| 이전(1턴) assistant가 `2024-1234`를 다시 적었나 | O | X (1턴 응답 비결정성) |

- 핵심은 **주문번호가 사라진 게 아니다.** `2024-1234`는 고장 런에서도 이전 user 메시지에 그대로 있었고, 실제로 모델은 응답에 `"2024-1234번 주문"`이라 적었다 — "맥락을 잃어서 못 불렀다"가 아니다.
- 결정적 차이는 **오염된 history가 QA의 "근거 없으면 못 한다고 답하라" 지시문을 *이전 user 발화처럼* 대화에 끼워 넣고 중복시켰다**는 점이다. 이 deferral 지시가 시스템 프롬프트의 **"Tool을 항상 먼저 호출하라"** 규칙과 경쟁했고, 고장 런에선 모델이 **Tool 호출 대신 사용자에게 상태를 되묻는 쪽**으로 기울었다. (이전 assistant가 주문번호를 다시 적지 않아 grounding이 약해진 것도 같은 방향으로 작용.)
- 단, temperature 0.3·단일 런이라 **이 인과를 100% 단정할 수는 없다**(§7). Tool 호출 여부는 실행마다 뒤집힐 수 있는 종류다. 다만 위 두 차이는 **오염(5-2)의 결정적 결과**이고, 관찰된 실패가 그 방향과 일치한다.
- 산출물: [정상 step2.json](advisor_order/responses/cases/step2.json)은 마지막(정상) 런으로 덮였으니, 보존본 [normal_step2_logs.log](advisor_order/responses/normal_step2_logs.log) · [swapped_step2_logs.log](advisor_order/responses/swapped_step2_logs.log) 참고.

## 6. 답: order를 뒤바꾸면 무엇이 깨지는가

- **검색 Context는 안 깨진다.** RAG는 그 턴 user 원문으로 검색하므로 order와 무관하게 같은 정책을 가져온다("환불" 키워드가 끌어옴).
- **Memory가 깨진다.** RAG가 먼저 돌면 RAG의 `Context information ...` 스캐폴딩이 user 메시지에 붙은 채로 대화 기억에 저장된다 → 이후 턴마다 기억이 보일러플레이트로 오염되고 입력 토큰이 샌다. (결정적·재현되는 구조적 차이)
- **그 오염 위에서 응답이 흔들린다.** 같은 2턴에서 정상은 `getOrderDetail`을 재조회해 상태 기반 안내를 했지만, 고장은 Tool을 부르지 않고 주문 상태를 되물었다.
- 결론: `order(10) → order(20)`(Memory 먼저)는 "Memory는 깨끗한 이전 대화를, RAG는 그 위에 정책을" 얹는 순서다. 뒤집으면 RAG 주입물이 Memory의 입력이 되어 **기억을 더럽힌다**. 정상 순서를 지켜야 하는 진짜 이유는 "검색어가 좋아져서"가 아니라 **"기억이 깨끗하게 유지돼서"**다.

## 7. 한계 / 비고

- 각 순서 1회 실행 — Ollama는 temperature 0.3으로 비결정적이라, **5-3의 응답 차이(Tool 호출 여부·표현)는 단일 런이라 인과를 완전히 분리하진 못한다.** 다만 5-2의 **Memory 오염은 프롬프트에 그대로 찍힌 결정적·재현되는 구조 차이**이며, 응답 저하는 그 오염과 일관된 방향이다.
- 5-1(두 런 Context 동일)·5-2(이전 user 메시지 오염)는 server.log 실측으로 확정.
- §4의 query rewriting(방향 A/B)은 **설계 제안**이며 이번 실험에서 구현·실측하지 않았다(`spring-ai-rag` 의존성 미추가).
- 실험으로 변경한 소스(`RagConfig.java`)는 git으로 복원 완료(`order(20)`).
