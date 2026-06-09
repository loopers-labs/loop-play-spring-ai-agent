# 3단계 — Memory + RAG 동시 적용 + Advisor 순서 실험 (평가 핵심 ★)

> Memory 와 RAG 가 같은 체인 위에서 각각 무슨 일을 하는지 관찰하고, Advisor 순서를 일부러 뒤바꿔
> 무엇이 깨지는지 본다. 2턴 대화: `"주문번호 2024-1234 배달 어디?"` → `"아까 그 주문 환불 돼요?"`

## Phase 3-1. 정상 순서 (memory order=10 → rag order=20)

T1 "2024-1234 배달 어디?" → `getDeliveryStatus(2024-1234)` ✅ → "역삼역 사거리, 약 15분 후".
T2 "아까 그 주문 환불 돼요?" → **되물음**("주문 번호를 알려주시면...") — 지시 대명사 해결 실패(1·2단계와 동일).

T2 프롬프트 구조 (`PerformanceLoggingAdvisor` dump):
```
#0 [USER]      주문번호 2024-1234 배달 어디?           ← Memory 주입
#1 [ASSISTANT] 주문 2024-1234는 현재 배달 중이며 ...   ← Memory 주입
#2 [SYSTEM]    [역할] 당신은 배달 서비스의 ...          ← system prompt
#3 [USER]      아까 그 주문 환불 돼요?
               + Context: "# 환불 기본 정책 ... 조리 시작 전 취소: CREATED/ACCEPTED ..."  ← RAG 주입(refund-basic)
```
→ Memory 가 한 일(1234 대화 복원)과 RAG 가 한 일(환불 정책 주입)은 전혀 다른 작업이고, 한 체인에서 맞물린다.

## Phase 3-2. 순서 뒤바꿈 (rag order=5 → memory order=10)

동일 2턴(세션 `chain-rev`). T2 → 역시 되물음.

## 관찰 기록 표

| 관찰 포인트 | `memory(10)→rag(20)` (정상) | `rag(5)→memory(10)` (고장 의도) |
|---|---|---|
| T2 메시지 구조 | #0 USER, #1 ASSISTANT, #2 SYSTEM, #3 USER | **완전히 동일** |
| T2 Context 정책 카테고리 | `# 환불 기본 정책` (refund-basic) | **동일** (`# 환불 기본 정책`) |
| Context 가 현재 주문(1234)과 관련? | 아니오 — "환불" 키워드로 검색됨, **1234 는 검색 쿼리에 없음** | 동일 |
| LLM 응답 정확도 | 되물음(지시 대명사 해결 실패) | 되물음(동일) |

## 헤드라인 발견 — 순서 뒤바꿈이 "아무것도 바꾸지 않았다" (quest 전제와 다름)

order 20 → 5 로 뒤집어도 **검색된 Context·메시지 구조·응답이 전부 동일**했다. quest 가 기대한
"RAG 가 Memory 보다 먼저면 1234 복원 전의 '아까 그 주문 환불 돼요?' 자체로 검색해 다른 결과가 나온다"는
이 시나리오에서 **재현되지 않았다.** 정직하게 원인을 따지면:

- `QuestionAnswerAdvisor` 는 **현재 turn 의 user query 텍스트("아까 그 주문 환불 돼요?")를 그대로 임베딩**해 검색한다.
- `MessageChatMemoryAdvisor` 는 이전 메시지를 프롬프트 앞에 **붙일 뿐, 현재 user query 를 재작성하지 않는다.**
- 두 advisor 가 **서로의 입력을 변형하지 않으므로**, 누가 먼저 실행되든
  ① RAG 가 임베딩하는 쿼리는 동일("아까 그 주문 환불 돼요?"), ② 최종 메시지 리스트도 동일.
- 그래서 **정상 순서에서도 RAG 는 1234 를 "복원한 질문"으로 검색한 적이 없다** — 1234 는 Memory 메시지(#0/#1)에만
  있고 검색 쿼리(#3 user text)엔 없다. Context 가 refund-basic 인 건 순전히 "환불"이라는 단어 때문이다.

> quest 의 전제("Memory 가 복원한 질문을 RAG 가 임베딩")가 성립하려면 **쿼리 재작성**이 필요하다.
> 그건 기본 `QuestionAnswerAdvisor` 가 아니라 `RetrievalAugmentationAdvisor` + `QueryTransformer`(대화 이력으로
> 쿼리를 다시 쓰는 심화 경로, 발표자료 4.4)에서나 일어난다. 기본 셋업엔 그 단계가 없다.

## 설계 결정 (README)

- **왜 Memory 가 RAG 보다 먼저인가?** — 이 기본 셋업에선 **검색 쿼리 관점에서 차이가 없다**(위 실험으로 증명).
  order 가 의미를 가지려면 advisor 가 서로의 입력을 변형해야 하는데, Memory↔QA 는 그렇지 않다.
  관례적으로 Memory(10)→RAG(20)→Performance(100)로 두지만, **검색 결과를 좌우하는 건 order 가 아니라
  "검색 쿼리가 무엇이냐"**다. "아까 그 주문"을 1234 로 바꿔 검색하고 싶다면 order 가 아니라 QueryTransformer 가 답이다.
- **반대 순서가 더 나은 상황이 존재하는가?** — quest 힌트는 "Memory 에 개인정보가 있어 임베딩하면 안 되는 경우"였다.
  그런데 기본 `QuestionAnswerAdvisor` 는 **user query 만 임베딩**하고 Memory 메시지 내용은 임베딩하지 않으므로,
  이 우려도 기본 셋업엔 해당하지 않는다(또 하나의 quest 가정 차이). 대화 이력이 통째로 쿼리에 들어가는 건
  `RetrievalAugmentationAdvisor` 가 history 를 쿼리에 합칠 때이며, 그때 비로소 "민감정보 임베딩"이 현실 위험이 된다.
  → Round 5 Guardrail 의 입력 마스킹과 직결.
- 실험 후 `order(20)` 복원 완료.

## 심화 검증 (B) — RetrievalAugmentationAdvisor 로 "의도된 breakage" 재현

기본 advisor 로는 순서가 안 깨졌으니, quest 가 기대한 breakage 가 정말 가능한지 **쿼리 재작성을 하는 심화 advisor**로
확인했다. `QuestionAnswerAdvisor` 대신 `RetrievalAugmentationAdvisor` + `CompressionQueryTransformer`(대화 이력으로
현재 질문을 standalone 쿼리로 재작성)로 갈아끼우고(`spring-ai-rag` 의존성 추가), 같은 2턴을 양 순서로 각 N=3 측정:

| 순서 | T2 "아까 그 주문" → 1234 해결 |
|---|---|
| memory(10) → rag-aug(20) (정상) | **2/3** (`getOrderDetail(2024-1234)` 호출 / 1234 응답) |
| rag-aug(5) → memory(10) (flipped) | **0/3** (전부 거절, tool 호출 0건) |

→ **이번엔 순서가 진짜로 깨뜨린다.** `CompressionQueryTransformer` 는 대화 이력으로 "아까 그 주문"→1234 를 재작성하는데,
flipped 면 RAG 가 Memory 보다 먼저 실행돼 **그 시점엔 이력이 프롬프트에 아직 없어** 재작성에 쓸 맥락이 없다 → 지시 대명사 해결 실패.
정상 순서면 Memory 가 먼저 이력을 넣어 줘서 재작성이 가능하다. (유일한 차이가 order(20↔5)이므로 순서가 원인)

즉 quest 전제("Memory 먼저 → RAG 가 복원된 질문 검색")는 **쿼리 재작성 메커니즘이 있을 때만** 성립한다.
3단계-A(기본 advisor)엔 그 메커니즘이 없어 안 깨졌고, (B)가 그 빠진 고리를 채워 의도된 breakage 를 재현했다.

부작용 관찰: `RetrievalAugmentationAdvisor` 의 기본 `ContextualQueryAugmenter`(allowEmptyContext=false)가
비정책 질문(T1 "배달 어디?")을 양 순서 모두 거절시켰다 — QA 기본 템플릿 함정의 advisor 버전.
그래서 (B)는 **채택하지 않고**(지시 대명사 해결을 2/3 살리지만 비정책 질문을 깨고 요청마다 압축 LLM 호출 비용 추가)
순서 영향의 실증으로만 문서화한다. 실제 채택은 allowEmptyContext + 비정책 라우팅이 필요 — Round 5 범위.

## 종합

순서 실험의 결론: **기본 advisor 로는 안 깨지고(A), 쿼리 재작성 advisor 로는 깨진다(B).**
그 차이가 "순서가 왜/언제 중요한가"를 정확히 규정한다 — **advisor 가 서로의 입력(특히 검색 쿼리)을 변형할 때만**
순서가 의미를 갖는다. 기본 Memory+QA 는 서로의 입력을 안 바꾸니 순서 무관(A), Compression 이 끼면 이력→쿼리
의존이 생겨 순서가 결정적(B). 시나리오 5 가 기본 셋업에서 안 되는 진짜 원인은 *순서*가 아니라 *지시 대명사 해결 자체*
(1·2단계 헤드라인: 정책 Fallback 지시 ↔ Memory 지시 대명사 해결 상충)이며, 그걸 고치려면 (B)의 쿼리 재작성 같은
아키텍처가 필요하다 — Round 5 방향.

## 학습 기록

→ Round 4 공통 학습 기록은 [README](../../../README.md) 의 *Round 4 — 공통 학습 기록* 참조.
