# Round 4 — RAG (검색 증강 생성, Retrieval-Augmented Generation)

배달 상담 에이전트에 **정책/FAQ 문서 기반 RAG**를 붙인다.
`classpath:/knowledge/*.md` 7건을 PgVector에 임베딩해 적재하고, 사용자 질문이 들어오면
`QuestionAnswerAdvisor`가 유사 문서를 검색해 프롬프트의 `Context:` 블록으로 주입한다.
3주차 Memory(대화 이력) 위에 4주차 RAG(정책 지식)를 **advisor 체인으로 합성**한다.

---

## 1단계 진행 상황

| 단계 | 핵심 산출물 | 상태 |
| --- | --- | --- |
| 구현 | `KnowledgeLoader`(ApplicationRunner) + `TokenTextSplitter`(800/350) + `PgVectorStore`(HNSW/COSINE/dim=1024) + `QuestionAnswerAdvisor`(topK=4, threshold=0.45, order=20) + advisor 체인 `memory(10)→rag(20)→performance(100)` | ✅ |
| 검증 | **시나리오 5종 × 3회** 실행 — 응답 본문 + DEBUG `Context:` 주입 블록 발췌 + `vector_store` 분포 + Top-K 코사인 직접 측정 | ✅ |
| 설계 결정 문서 | 청크 800/350 · Top-K=4 · advisor order(20) · threshold=0.45 — 4종 "왜?" | ✅ |
| 2단계 — 청킹 전략 실험 | **(검색 레벨)** chunkSize 200/800/2000(+200·min50) 청크수·peak 코사인·완전성 → [설계 결정 1](#1-왜-청크-크기-800--min-350-인가) · **(end-to-end)** A(800)/B(100)/C(2000) row수·평균토큰·답변품질 → [2단계 섹션](#2단계-청킹-전략-실험-abc-end-to-end): **B는 49 row·조각남으로 품질↓, C는 A와 무차별, A 최적** | ✅ |
| 2단계 — Top-K ablation | **K=1/4/10 × threshold(0.5/OFF) 정량 비교**(검색 레벨) — 주입 문서수·복합질문 커버·비용 측정으로 "K=4" 근거 검증 → [설계 결정 2](#2-왜-top-k4-인가) | ✅ |
| 2단계 — Advisor order 증거 | **memory↔rag order의 검색 불변성 + memory 재작성 역효과(0.54→0.50) 정량 측정** — order의 가치는 검색이 아니라 가시성·측정임을 증명, 설계 결정 3 교정 → [설계 결정 3](#3-왜-questionansweradvisororder20-인가) | ✅ |
| 3단계 — threshold ablation | **0.30~0.70 sweep**(recall·noise·empty 정량) → 0.5는 정답 25% 누락, **0.45 채택** → `SIMILARITY_THRESHOLD` 0.5→**0.45** 변경 + **end-to-end 재검증**(시나리오 2 Fallback **0/3→3/3** 개선, 3은 LLM 문제로 불변) → [설계 결정 4](#4-similaritythreshold045-의-근거-3단계-ablation) | ✅ |
| 1단계 보강 — Fallback 과발동 수정 | `QuestionAnswerAdvisor` 기본 영어 템플릿 → **한국어 재균형 템플릿**(근거 있으면 사용·빈 컨텍스트만 거절) → 시나리오 3 **✗3/3 → △○○**(정답 인용), 안전·빈컨텍스트 거절 유지 → [Fallback 수정 섹션](#fallback-과발동-수정--qa-템플릿-재균형) | ✅ |
| 2단계 — 실패 관찰 | **(조각남 정량)** Q2 핵심사실 A 4/4·B 1/4·deflect A1/B4 + B=100 8조각 retrieval / **(Fallback 없는 환각)** 가드 레이어드 제거 ablation → A3에서 **메뉴 환각 재현**, threshold만으론 환각 못 막음 입증 → [실패 관찰 ①](#실패-관찰--정량-지표-조각남-객관-측정)·[②](#실패-관찰--fallback-가드-없는-환각-레이어드-ablation) + [2단계 설계 결정](#설계-결정-문서-2단계) | ✅ |
| 3단계 — Memory+RAG + Advisor 순서 | **2턴 대화로 Memory 주입·RAG Context·응답 캡처** + `order(20)→(5)` 스왑 실험 → **정상=스왑(검색·Memory·응답 동일)**: QA Advisor가 순서 무관하게 현재 턴 텍스트만 임베딩 → 관찰 기록 표 → [3단계 섹션](#3단계-memory--rag-동시-적용--advisor-순서-실험) | ✅ |
| 선택 4.4 — RetrievalAugmentationAdvisor | **RAA + CompressionQueryTransformer 구현/실험**(`@Profile("raa")`, `spring-ai-rag`) → **order 스왑 실측**: memory→RAA는 "2024-1234"로 질의 복원 / RAA→memory는 미복원("Did my recent order…") → **RAA에선 order가 검색을 실제로 바꿈**(stock QA와 대비). 단 융합/안정성은 미개선(tool 깨짐·언어 드리프트) → stock QA가 실용적 → [선택 과제 4.4](#선택-과제-44--retrievalaugmentationadvisor-raa-심화) | ✅ |

> ⚠️ **핵심 결론 먼저**: RAG 파이프라인(임베딩·검색·주입·중복방지)은 **3/3 재현 동작**한다.
> 그러나 **응답 품질의 병목은 검색이 아니라 LLM(qwen2.5)** 이다 — 정답 문서가 Context에 들어가 있어도
> 시나리오 2·3은 3/3 모두 Fallback("정책 정보를 찾지 못했습니다")을 냈다. 상세는 [핵심 Finding](#핵심-finding) 참고.

---

## 구현 개요

### RAG 파이프라인

```
[기동 시 1회] knowledge/*.md ─ KnowledgeLoader ─ TokenTextSplitter ─ embedding ─▶ PgVector(vector_store)
                                  (파일명 {category}__{id}.md → metadata)        (qwen3-embedding:0.6b, 1024-dim)

[요청 시]  user query ─▶ MessageChatMemoryAdvisor(10) ─▶ QuestionAnswerAdvisor(20) ─▶ LLM(qwen2.5)
                          이전 대화 이력 주입              similaritySearch(topK=4, thr=0.5)
                                                          → [정책 문서] Context 블록 주입
```

### Advisor 체인 순서 (왜 이 순서인가는 [설계 결정 3](#3-왜-questionansweradvisororder20-인가) 참고)

| advisor | order | 역할 |
| --- | --- | --- |
| `MessageChatMemoryAdvisor` | 10 | 이전 대화 이력 주입 (3주차) |
| `QuestionAnswerAdvisor` | 20 | VectorStore 검색 결과 `Context:` 주입 (4주차) |
| `PerformanceLoggingAdvisor` | 100 | 응답시간·토큰 집계 |
| `SimpleLoggerAdvisor` | **30** | request/response 본문 DEBUG 로깅 (`local`/`dev` 한정) — *아래 Observability 참고* |

### 설정값 (`RagConfig`, `application.yml`)

| 항목 | 값 | 근거 |
| --- | --- | --- |
| chunkSize / minChunkSizeChars | **800 / 350** | [설계 결정 1](#1-왜-청크-크기-800--min-350-인가) |
| Top-K | **4** | [설계 결정 2](#2-왜-top-k4-인가) |
| similarityThreshold | **0.45** (3단계 ablation, 기존 0.5) | [설계 결정 4](#4-similaritythreshold045-의-근거-3단계-ablation) |
| index-type / distance | HNSW / COSINE | pgvector 기본 권장 |
| dimensions | 1024 | qwen3-embedding:0.6b 출력 차원 |

---

## 검증 — 시나리오 5종 × 3회

원본: [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) **PART A** (응답 본문 + Context 발췌 + perf 전량)

### 종합 — RAG 인프라는 동작, 응답 품질은 LLM이 병목

| # | 시나리오 | 기대 | RAG 검색(Context 주입) | LLM 응답 품질 | 안전/정확 판정 |
| --- | --- | --- | --- | --- | --- |
| 1 | 비 오는 날 보상 | weather/delay Top-K, 원문 수치 | ✅ **3/3** delay-compensation(0.59)+weather-delay(0.53) | ✅ 정책 근거 답변(기상특보·60분·"비만으론 불가") | **3/3 ✅** |
| 2 | 결제 후 취소 환불 | cancel-policy Top | △ refund-basic(0.53) 주입 / cancel-policy(0.49) **필터** | ❌ **3/3 Fallback** (refund-basic 있어도 미사용) | **0/3 ❌** |
| 3 | 쿠폰 중복 | coupon-faq Top, 원문 | ✅ **3/3** coupon-faq(0.64, 정답 문서) | ❌ **3/3 Fallback** (정답 문서 있어도 미사용) | **0/3 ❌** |
| 4 | 사장님 전화번호 | privacy 히트 + 거절 | ❌ **3/3** privacy(0.48) **필터** → `qa=[]` | ✅ **3/3 거절**(`[금지]` 규칙) — 전화번호 미노출 | **3/3 ✅(안전)** |
| 5 | Memory+RAG (2턴) | orderId 유지 + 환불정책 검색 + 상태 맞춤 답변 | ✅ Q2 환불+취소정책 주입 / Memory에 2024-1234 유지 | △ **3/3 재질문** (orderId·정책 보유했으나 융합 실패) | **부분** |

판정 요약: **검색·주입·Memory 유지·중복방지 = 인프라 전부 3/3.** 최종 응답은 시나리오 1·4만 기대 충족(4는 안전 측면), 2·3은 모델이 Fallback, 5는 모델이 융합 실패.

### Top-K 코사인 유사도 직접 측정 (기준선 threshold=0.5)

`qwen3-embedding:0.6b`로 질의를 임베딩해 `vector_store` 전 row와 `1-(embedding <=> q)` 계산.
원본: [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) **PART B**

| 질의 | 1위 | 2위 | 주입된 문서 |
| --- | --- | --- | --- |
| 비 오는 날 보상 | delay-compensation **0.59 ✅** | weather-delay **0.53 ✅** | 2건 |
| 결제 후 취소 환불 | refund-basic **0.53 ✅** | cancel-policy 0.49 ✗ | **1건** (기대한 cancel-policy 탈락) |
| 쿠폰 중복 | coupon-faq **0.64 ✅** | cancel-policy 0.30 ✗ | 1건 |
| 사장님 전화번호 | privacy 0.48 ✗ | cancel-policy 0.28 ✗ | **0건** |
| (5-Q1) 2024-1234 배달 어디쯤 | refund-basic 0.37 ✗ | cancel-policy 0.35 ✗ | 0건 (Tool 호출 질의) |
| (5-Q2) 아까 그 주문 환불 돼요? | refund-basic **0.54 ✅** | cancel-policy **0.51 ✅** | 2건 |

> 관찰: qwen3-embedding의 한국어 정책 문서 코사인은 **정답 문서가 0.48~0.64에 몰린다.**
> 0.5는 이 분포의 한가운데라 경계 문서(cancel-policy 0.49, privacy 0.48)가 아슬아슬하게 잘린다(→ 3단계에서 0.45로 변경). → [설계 결정 4](#4-similaritythreshold045-의-근거-3단계-ablation)

### 시나리오별 응답 + Context 발췌 (대표 1회, 전량은 raw)

**시나리오 1 — 기상지연보상 (✅ Context 주입 + 정책 근거 답변)**
- 응답(R3): *"보상 여부는 기상 특보와 실제 지연 시간을 기준으로 결정됩니다. 비가 오는 날짜만으로 보상을 받을 수는 없습니다. … 예상 시간보다 **60분 이상 지연**된 경우 전액 환불 검토. **기상 특보가 사전 고지되지 않았거나** 예상 시간이 변경되지 않은 채 60분 이상 지연된 경우 보상 가능."*
- 주입된 Context: `# 배달 지연 보상 기준`(지연시간별 표: 11~29분 1,000원 쿠폰 / 30~59분 배달비 환불·3,000원 쿠폰 / 60분+ 전액환불) + `# 기상 악화 시 배달 지연 안내`.
- ⚠️ 사소한 품질 흠: R1은 *"# 배달 지연 보상 기준 및 # 기상 악화 …섹션을 참조해 주세요"* 처럼 **마크다운 헤더를 응답에 노출**(원문 청크 누수). 정답은 맞으나 표현 정제 필요.

**시나리오 2 — 결제 후 취소 환불 (❌ 3/3 Fallback)**
- 응답(R1·R2·R3 동일 취지): *"현재 안내해 드릴 수 있는 정책 정보를 찾지 못했습니다. 확인 후 정책에 따라 안내드리겠습니다. 어떤 주문을 말씀하시는 건가요?"*
- 그러나 Context에는 `# 환불 기본 정책`의 **"조리 시작 전 취소: 주문 상태 CREATED/ACCEPTED면 고객 요청으로 전액 즉시 취소/환불 가능"** 이 들어가 있었다(`qa_retrieved_documents=[Document … 환불 기본 정책]`, 입력토큰 3689 = 빈컨텍스트 3254 + 약 435).
- → **검색은 성공했으나 모델이 그 문서를 쓰지 않고 Fallback을 선택.**

**시나리오 3 — 쿠폰 중복 (❌ 3/3 Fallback, 가장 분명한 모델 실패)**
- 응답(3R 동일): *"현재 안내해 드릴 수 있는 정책 정보를 찾지 못했습니다 …"*
- Context에는 `# 쿠폰 사용 FAQ`의 **"## 쿠폰 중복 사용 — 할인 쿠폰 + 배달비 쿠폰 → 가능 / 할인 쿠폰 + 할인 쿠폰 → 불가"** 가 그대로 들어 있었다(coupon-faq 0.64, 6개 시나리오 중 **최고 유사도**, 입력토큰 3677).
- → 정답이 토씨까지 들어있는데도 3/3 Fallback. **검색 품질 문제가 전혀 아님을 입증하는 케이스.**

**시나리오 4 — 사장님 전화번호 (✅ 3/3 거절, 전화번호 미노출)**
- 응답(3R): *"사장님의 개인정보(전화번호 등)는 안내할 수 없습니다."* (R1/R2/R3 표현만 다름)
- Context: `qa_retrieved_documents=[]` (privacy 0.48 < 0.5로 **검색 0건**). 즉 **RAG가 아니라 System Prompt `[금지]` 규칙**("사장님·라이더·고객의 개인정보를 노출하지 않습니다")이 거절을 보장했다.
- → 안전 속성은 **방어 심층화**로 유지됨(RAG 히트 실패 ≠ 정보 유출). 전화번호 문자열은 3회 모두 응답·로그 어디에도 등장하지 않음.

**시나리오 5 — Memory+RAG 협업 (△ 인프라 OK, 융합 실패)**
- Q1 *"주문번호 2024-1234 배달 어디쯤이에요?"* → `getDeliveryStatus` Tool 호출(입력토큰 ~6657), 응답: *"역삼역 사거리 부근 배송 중, 예상 도착 오후 8시 5분"*. ✅
- Q2 *"아까 그 주문 환불 돼요?"* → RAG가 `# 환불 기본 정책` + `# 주문 취소 정책` **2건 주입**(refund-basic 0.54 / cancel-policy 0.51). 그러나 응답: *"주문 번호를 다시 확인해 주세요"*(R1·R2) / *"주문 상태와 환불 사유를 알려주시면…"*(R3).
- **Memory 유지 증명** — `GET /api/v1/session/memo-rag-r3/messages` 4건 모두 보존:
  ```json
  [{"type":"USER","content":"주문번호 2024-1234 배달 어디쯤이에요?"},
   {"type":"ASSISTANT","content":"주문 2024-1234의 배달 상태는 … 역삼역 사거리 …"},
   {"type":"USER","content":"아까 그 주문 환불 돼요?"},
   {"type":"ASSISTANT","content":"주문 상태와 환불 사유를 알려주시면 …"}]
  ```
  → **orderId 2024-1234는 이력에 살아 있고(Memory ✅), 환불 정책도 검색됨(RAG ✅).** 다만 모델이 둘을 엮어 "이 주문은 DELIVERING이므로 반품 절차" 같은 **상태 맞춤 답**을 못 만들고 재질문했다. (Q1은 `getDeliveryStatus`로 위치만 받고 *주문 상태값*은 안 받아서, 모델이 환불 분기에 필요한 status를 못 가진 점도 영향.)

---

## 핵심 Finding

1. **Fallback에는 두 종류가 있다 — 검색 누락형 vs LLM 과발동형.** (threshold 0.5→0.45 end-to-end 재검증으로 분리 입증, [PART H](raw/scenarios-1to5.txt))
   - **(a) 검색 누락형 — 시나리오 2**: 0.5에선 cancel-policy(0.49)가 잘려 refund-basic만 주입 → 모델이 "조리 전/후" 분기를 확신 못 해 **3/3 Fallback**. **0.45로 cancel-policy를 추가 주입하니 3/3 정상 답변**(CREATED/ACCEPTED→전액환불, COOKING→불가). → 원인은 LLM이 아니라 **검색 누락**이었고 threshold로 해결됨.
   - **(b) LLM 과발동형 — 시나리오 3**: coupon-faq(0.64, `## 쿠폰 중복 사용` 조항 통째 포함)는 0.5·0.45 양쪽 다 주입됐는데도 **3/3 Fallback**. → 정답이 눈앞에 있어도 모델이 안 쓰는 **순수 LLM 실패**(threshold·검색으로 해결 불가, 프롬프트 영역). → **[QA 템플릿 재균형으로 수정함](#fallback-과발동-수정--qa-템플릿-재균형): ✗3/3 → △○○.**
2. **Fallback 과발동(2-b)의 원인.** System Prompt의 *"(Fallback) 정책 문서가 비어 있거나 관련 조항이 없으면 …"* + QuestionAnswerAdvisor 기본 템플릿의 *"If the answer is not in the context, inform the user that you can't answer"* 가 겹쳐, qwen2.5가 **근거가 있어도 "없다"고 판단**하는 보수적 실패. (컨텍스트 용량 여유는 충분했으므로 절단 문제 아님 — 순수 판단 실패.)
3. **threshold 0.5는 한국어 임베딩 분포 한가운데라 경계 문서를 자른다 → 0.45로 변경.** cancel-policy(0.49)·privacy(0.48)가 0.5에서 탈락했으나 0.45에선 통과. 재검증 결과: 시나리오 2는 **0/3→3/3 개선**, 시나리오 4는 privacy 문서를 근거로 확보(거절은 유지). 정량 sweep·재검증은 [설계 결정 4](#4-similaritythreshold045-의-근거-3단계-ablation).
4. **안전은 단일 장치가 아니라 방어 심층화로 달성된다.** 시나리오 4에서 RAG(privacy)는 못 맞췄지만 System Prompt `[금지]`가 전화번호 노출을 막았다. → similarityThreshold 하나로 환각/유출을 100% 막을 수 없고, **Fallback 프롬프트 + 금지 규칙과의 협업**이 필수.
5. **Memory와 RAG는 독립적으로는 잘 동작하나, 둘을 엮는 "추론"은 모델 몫이다.** 시나리오 5에서 orderId 유지(Memory)·환불정책 검색(RAG)은 성공했지만 상태 맞춤 답변 생성은 실패. *인프라를 깔아줘도 추론 능력은 못 심는다*(3주차 결론과 동일 계열).

---

## Fallback 과발동 수정 — QA 템플릿 재균형

> **(1단계 보강)** — 1단계 검증에서 관찰된 실패를 1단계 범위에서 수정한 것이므로 여기에 둔다.

[핵심 Finding 1-(b)](#핵심-finding)에서 드러난 **LLM Fallback 과발동**(정답 문서가 주입돼도 모델이 "정책 정보를 찾지 못했습니다"라고 거절 — 시나리오 3, 모든 chunk/threshold에서 ✗ 3/3)을 수정했다. 원본: [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) **PART J**.

**원인**: `QuestionAnswerAdvisor` **기본 템플릿(영어)**이 *"If the answer is not in the context, inform the user that you can't answer"* 로 끝나, System Prompt의 한국어 Fallback 규칙과 **'거절'을 이중으로 부추겼다**. qwen2.5가 coupon-faq의 `## 쿠폰 중복 사용` 조항이 눈앞에 있어도 "없다"고 오판.

**수정(삭제 아닌 재균형)**: `RagConfig`에서 `QuestionAnswerAdvisor.promptTemplate()`에 한국어 템플릿(`QA_PROMPT_TEMPLATE`)을 주입 — *"근거가 일부라도 있으면 반드시 사용하고, 컨텍스트가 비었거나 전혀 무관할 때만 Fallback"*. (빈·무관 컨텍스트의 안전한 거절은 System Prompt `[금지]`·`[정책 인용 규칙]`이 계속 담당.)

**결과 (threshold 0.45·chunk 800 동일 조건)**:

| 시나리오 | before (기본 영어 템플릿) | after (한국어 재균형) |
| --- | --- | --- |
| 3 쿠폰 중복 | ✗ **3/3 Fallback** | **△○○** — coupon-faq의 "할인+배달비 가능 / 할인+할인 불가" 인용 |
| 1 기상보상 | ○ | ○ (무영향) |
| 2 결제취소 | ○ | ○ (무영향) |
| 4 전화번호 | ✅ 거절 | ✅ 거절 (전화번호 미노출 유지) |
| 5 도메인 밖 | ○ 거절 | ○ 거절 (점심 추천 안 함 유지) |

→ **검색은 됐는데 LLM이 거절하던 과발동을 ✗3/3 → 정답 사용 3/3으로 개선**, 안전(S4)·빈 컨텍스트 거절(S5)은 그대로. **잔존 한계**: R1처럼 *답 + Fallback 한 줄*이 함께 나오는 qwen2.5 비일관(모델 한계). System Prompt Fallback 문구를 더 완화하면 줄일 수 있으나, 과도 완화 시 빈 컨텍스트 환각 위험과 트레이드오프라 **QA 템플릿 한 곳만** 손대 멈췄다.

---

## 설계 결정 문서

### 1. 왜 청크 크기 800 / min 350 인가?

**근거(정성)**: 배달 정책 문서는 이미 "조항 단위"로 짧게 쪼개져 있다. 7개 `.md`는 각각 단일 주제(환불/취소/쿠폰/지연/개인정보)이고 글자수 572~722자(≈400~450토큰)다. 800이면 한 정책이 **쪼개지지 않고 통째로** 한 벡터에 담긴다.

**근거(정량 — 2단계 ablation, 검색 레벨)**: chunkSize를 바꿔 재시드하고 청크 수·peak 코사인·완전성을 측정했다. 원본: [`raw/stage2-chunking.txt`](raw/stage2-chunking.txt) **PART D**.

| chunkSize / min | 총 청크 | 문서당 | 'coupon 중복+최소주문 한 청크' | "쿠폰 중복" peak cos | "전화번호" privacy peak cos |
| --- | --- | --- | --- | --- | --- |
| 200 / 350 | 21 | 3 | **0 (분리됨)** | 0.6151 | 0.4927 |
| **800 / 350 (선택)** | **7** | **1** | **1 (온전)** | 0.6380 | 0.4820 |
| 2000 / 350 | **7** | **1** | 1 (온전) | 0.6380 | 0.4820 |
| 200 / 50 (강제분할) | 24 | 3~4 | 0 (분리됨) | **0.7305** | **0.5164** |

이 표에서 4가지 사실이 정량적으로 나온다:

1. **800 == 2000 (이 코퍼스에선 바이트까지 동일).** 두 조건 모두 7청크·문서당 1개·글자수 722/572/692…가 **완전히 일치**, 코사인도 소수점까지 동일(0.5918/0.5254…). → 문서가 chunkSize보다 짧으면 chunkSize를 키우는 건 **순수 낭비**. 800은 최대 문서(722자)를 통째 담는 **최소값**.
2. **작게 쪼갤수록 peak 코사인이 올라간다.** "쿠폰 중복"이 0.638(통째) → **0.731**(200/50, 정확히 `## 쿠폰 중복 사용` 조각). focused 조각이 주제 밀도가 높아서다. 심지어 "전화번호" privacy가 0.482(통째, **0.5 미달로 탈락**) → **0.516**(200/50, **0.5 통과**) — 즉 분할은 경계 문서의 recall을 끌어올린다.
3. **그러나 분할은 "완전성"을 깨뜨린다.** 200에서는 `쿠폰 중복`과 `최소 주문 금액`이 **서로 다른 청크로 분리(둘 다 보유=0)**. 800/2000은 둘이 한 청크에 온전(=1). 또 200의 검색 결과 청크는 *문장 중간*에서 시작한다(`"없이 자동으로 쿠폰이 지급…"`, `"IVERING(배달 중)…"`) — 제목·맥락이 없는 조각이 LLM에 들어간다. 800은 모든 청크가 `# 정책제목`으로 시작해 출처가 명확하다.
4. **min 350의 역할**: 200/350(문서당 3청크) vs 200/50(문서당 3~4청크, 47·59·72자짜리 **잔챙이 조각** 추가 생성). min 350은 이런 350자 미만 부스러기를 앞 청크에 병합해 **무의미한 미니 벡터를 막는다.**

**결론**: 이 도메인(정책 Q&A)은 *정확도(peak recall)보다 완전성*이 중요하다. 환불 정책의 "가능 케이스"만 잘려 검색되고 "불가 케이스"가 빠지면 위험한 반쪽 답이 된다. 따라서 **"1 정책 = 1 청크 = 1 벡터"가 되는 800**을 택한다(2000은 동일 효과의 낭비, 200은 peak는 높지만 완전성·맥락 손실).

**다른 도메인이라면?**
- *블로그 글 / 장문 PDF*: 한 문서에 여러 주제가 길게 섞인다. 800은 너무 커서 "서론+본론+결론"이 한 벡터에 뭉쳐 **precision이 떨어진다.** → 200~400으로 줄이고 **오버랩(50~100)**을 둬 문단 경계에서 의미가 잘리지 않게. (위 ablation의 "분할이 peak를 올린다"가 이때는 장점이 됨.)
- *사용자 리뷰 10만 건*: 리뷰 1건이 곧 1청크(수십 토큰). chunkSize는 거의 무의미하고 **리뷰를 서로 묶지 않는 것**이 핵심. 규모상 임베딩 비용·인덱스 크기가 지배 변수.

### 2. 왜 Top-K=4 인가?

정책 문서 **총 7건**에서 1 / 4 / 10 중 선택. K와 threshold는 함께 작동하므로, 같은 벡터스토어(800)에서 5개 질의의 7문서 전체 코사인 랭킹을 뽑아 K별 거동을 계산했다. 원본: [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) **PART E**.

**① threshold=0.5 고정 시 K별 실제 주입 문서 수** — K=4와 K=10이 모든 질의에서 동일:

| 질의 | 0.5 통과 | K=1 | K=4 | K=10 |
| --- | --- | --- | --- | --- |
| 비 오는 날 보상 | 2 | 1 | **2** | 2 |
| 결제 후 취소 | 1 | 1 | 1 | 1 |
| 쿠폰 중복 | 1 | 1 | 1 | 1 |
| 사장님 전화번호 | 0 | 0 | 0 | 0 |
| 아까 그 주문 환불 | 2 | 1 | **2** | 2 |

→ 0.5를 넘는 문서가 어느 질의든 **≤2건**이라, **K=4면 이미 다 담기고 K=10은 추가 이득 0**. (K=10은 K=4의 상위집합이지만 threshold가 잘라 결과가 같아짐.)

**② K=1의 위험 — 복합 질문 정답 커버 (threshold 0.5):**

| 질의 | 필요 문서 | K=1 | K=4 |
| --- | --- | --- | --- |
| 비 오는 날 보상 | delay+weather (2) | **1/2** | 2/2 |
| 아까 그 주문 환불 | refund-basic+cancel (2) | **1/2** | 2/2 |
| 쿠폰 중복 | coupon (1) | 1/1 | 1/1 |

→ 2개 정책이 함께 필요한 질문에서 **K=1은 두 번째 정답을 놓친다(1/2)**. 예: "아까 그 주문 환불?"은 refund-basic(0.54)+cancel-policy(0.51)이 둘 다 필요한데 K=1은 refund만 담아 "조리 전/후" 분기를 못 답함. K=4면 2/2.

**③ K=10 = threshold 무력화 + 비용 (threshold OFF 기준):**

| 지표 | K=1 | K=4 | K=10 |
| --- | --- | --- | --- |
| 주입 문서 수 | 1 | 4 | **7 (전체 코퍼스)** |
| 주입 글자수(≈비용) | ~640 | ~2,700 | **~4,634** |

→ threshold가 없거나 느슨하면 K=10은 무조건 **전체 7문서**를 주입(무관 문서 포함, 도메인 경계 질문에서 환각 유발) + 비용 **K=4의 1.7배**.

**결론**: **K=4**는 "환불+취소"처럼 2~3개 정책이 얽히는 복합 질문을 커버(②, 2/2)하면서, threshold(0.5)가 꼬리 문서를 잘라 실제 주입은 0~2건으로 수렴(①)한다. **recall은 K=4가 확보, precision은 threshold가 담당**하는 역할 분담이 성립한다. K=1은 복합 질문에서 위험(②), K=10은 이득 없이 비용·환각 위험만 증가(①③).

### 3. 왜 `QuestionAnswerAdvisor.order(20)` 인가?

체인: **`memory(10) → rag(20) → performance(100)`** (낮을수록 먼저/바깥). order는 *검색을 바꾸는 파라미터가 아니라 "각 advisor가 무엇을 보는가"의 순서*다. 그래서 정량 증거도 (검색 불변성)·(재작성 역효과)·(가시성 메커니즘)으로 잡았다. 원본: [`raw/stage3-memory-rag-order.txt`](raw/stage3-memory-rag-order.txt) **PART F**.

**① 현재 구현에서 memory↔rag order는 검색 결과를 바꾸지 않는다.** `QuestionAnswerAdvisor`는 *현재 턴 user text*만 임베딩한다(이력 미포함). 실측 대조:
- 시나리오 5-Q2(memory 보유, PART A) 주입 문서 = `환불 기본 정책`+`주문 취소 정책`
- 동일 질의 standalone(PART E) Top-2 = refund-basic 0.54 + cancel-policy 0.51 → **동일**

→ memory 주입 유무와 무관하게 검색 문서 집합이 같다. 즉 memory(10)↔rag(20)를 바꿔도 검색은 불변.

**② 그렇다면 "memory로 질의를 재작성하면 검색이 좋아진다"는 통설은? 이 도메인에선 오히려 악화된다.**

| 질의 변형 | Top-1 / Top-2 | 0.5 통과 |
| --- | --- | --- |
| `아까 그 주문 환불 돼요?` (현재 원문) | refund 0.5408 / cancel 0.5083 | **2건** |
| `주문번호 2024-1234 환불 돼요?` (memory가 orderId 주입) | refund 0.4997 / cancel 0.4532 | **0건** ← 둘 다 탈락 |
| `2024-1234` (orderId 단독) | privacy 0.3220 | 0건 |
| `아까 그거 어떻게 돼요?` (순수 대명사) | privacy 0.1725 | 0건 |

→ orderId `2024-1234`는 **정책 문서의 검색 신호가 아니다**(정책=상태·규칙, 주문번호 아님). memory가 orderId를 붙이면 "환불" 의미가 희석돼 0.54→0.50으로 떨어지고 threshold를 탈락한다. **정책 검색 신호는 현재 턴의 키워드(환불/취소/쿠폰)에서 나오며 이미 현재 턴에 있다.** → naive memory 재작성은 정책 RAG를 *개선이 아니라 악화*.

**③ 그럼 order는 왜 중요한가 — "검색"이 아니라 "가시성·측정".** 직접 실측 증거(이미 겪음): `SimpleLoggerAdvisor` order(20)→(30) 사건 — order==rag(20)일 때 request 로그에 RAG의 Context 블록이 **누락**, order>rag(30)에서 **출현**(아래 Observability 섹션). 즉 *"advisor는 자기보다 낮은 order의 산출물만 본다"*. 같은 원리로 `performance(100)`는 항상 가장 바깥이어야 **최종 LLM 호출의 토큰/시간을 온전히 측정**한다(rag보다 안쪽이면 주입 전 토큰을 재 집계가 틀어짐).

**결론**: memory→rag→performance 순서는 옳지만, 그 근거는 *"검색 개선"이 아니라 "정확한 결합·측정"*이다. 이 도메인에서 order의 검색상 이득은 ~0이며(①), 발현되려면 **다른 설계**(주문번호로 키된 *주문별 문서* RAG, 또는 정책 의도까지 추론하는 질의 재작성)가 필요하다 — 단순 orderId 주입은 오히려 해롭다(②).

### 4. `similarityThreshold`=0.45 의 근거 (3단계 ablation)

처음엔 0.5로 출발했으나, **3단계 정량 sweep 결과 0.45가 더 낫다고 판단해 변경**했다. threshold는 검색 필터일 뿐이라 동일 벡터스토어(800)에서 코사인 랭킹만으로 sweep을 계산했다(topK=4 동시 적용). 정답(relevant) = "그 질문에 실제로 답이 되는 정책 문서". 원본: [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) **PART G**.

| threshold | recall(정답 8건) | noise(무관 주입) | empty(검색 0건=Fallback) | 완전커버 |
| --- | --- | --- | --- | --- |
| 0.30~0.35 | 100% | **6** | 0 | 5/5 |
| 0.40 | 100% | 4 | 0 | 5/5 |
| **0.45 (채택)** | **100%** | **2** | **0** | **5/5** |
| 0.48 | 100% | 2 | 0 | 5/5 |
| **0.50 (기존)** | **75%** | 0 | **1** | **3/5** |
| 0.55 | 25% | 0 | 3 | 1/5 |
| 0.65~0.70 | 0% | 0 | 5 | 0/5 |

- **0.50의 문제**: noise는 0이지만 **정답 25%를 놓친다** — cancel-policy(0.49)·privacy(0.48)가 탈락, 시나리오 4는 검색 0건(Fallback). 완전커버 3/5.
- **0.45가 최적**: 정답 **8/8 전부 적중·완전커버 5/5·empty 0**, noise는 단 2건. 그 2건은 `refund-after-delivered`(0.486/0.485) — **같은 환불 도메인** 문서라 위험 낮은 mild noise. (0.40 미만은 noise가 4~6으로 급증.)
- **왜 0.48이 아니라 0.45?** 가장 낮은 정답 privacy(0.482)·cancel(0.4915)가 0.48에 너무 붙어 임베딩 미세변동에 깨지기 쉽다. 0.45는 그 아래로 **~0.03 여유**를 줘 견고하다.
- **너무 낮으면(0.3)**: noise 6건 — 도메인 밖/약한 문서까지 끼어 LLM이 근거로 오해해 환각 위험. **너무 높으면(0.7)**: 정답 최고치 coupon 0.64도 잘려 전 시나리오 검색 0건.
- **한계(여전히 유효)**: threshold만으로 환각·안전을 100% 보장 못 한다. 시나리오 4는 0.45에서 privacy가 검색되지만, 검색이 0건이어도 안전을 지키는 것은 **Fallback 프롬프트 + `[금지]` 규칙**이다(방어 심층화).

**end-to-end 재검증 (0.5 vs 0.45, 각 5종×3회)** — 원본 [PART H](raw/scenarios-1to5.txt):

| 시나리오 | 0.50 (PART A) | 0.45 | 판정 |
| --- | --- | --- | --- |
| 1 기상지연 | ✅ 3/3 답변 | ✅ 3/3 답변 | 동일 |
| 2 결제후취소 | ❌ 3/3 Fallback | ✅ **3/3 정상 답변** | **🎯 개선 (0/3→3/3)** |
| 3 쿠폰중복 | ❌ 3/3 Fallback | ❌ 3/3 Fallback | 변화 없음 (LLM 문제) |
| 4 전화번호 | ✅ 거절 (qa=[]) | ✅ 거절 (privacy 주입) | 안전 유지 + 근거 확보 |
| 5 Memory+RAG | △ Q2 재질문 | △ Q2 재질문 | 융합 실패 그대로 |

→ **0.45가 시나리오 2의 Fallback을 0/3→3/3로 실제로 고쳤다**(cancel-policy 추가 주입 효과). 시나리오 3은 정답 문서가 양쪽 다 있었으므로 불변(=LLM 과발동, threshold 무관). 시나리오 4는 privacy를 근거로 확보하되 거절 유지. (시나리오 5 Q1은 0.45 재실행 중 R1·R2가 ollama 간헐 500 — infra 이슈, threshold 무관.) 상세 해석은 [핵심 Finding 1](#핵심-finding).

---

## 2단계: 청킹 전략 실험 (A/B/C, end-to-end)

`TokenTextSplitter`를 3가지로 바꿔 같은 5질문을 돌리고 **검색 품질·입력 토큰·청크 수**를 정량 비교한다. 각 실험 전 `TRUNCATE vector_store` → 앱 재기동(`--rag.chunk-size`/`--rag.min-chunk-size-chars` override) → `KnowledgeLoader` 재적재. (설계 결정 1의 [검색 레벨 ablation(PART D)](#1-왜-청크-크기-800--min-350-인가)와 달리 여기선 **LLM 응답까지** 본다.) 원본: [`raw/stage2-chunking.txt`](raw/stage2-chunking.txt) **PART I**(fix 전 1차) · **PART N**(post-fix 재실행).

**질문 5종**: ① 비 오는 날 보상 ② 결제 후 취소 ③ 쿠폰 중복 ④ 배달 완료 후 환불 ⑤ 오늘 점심 뭐 먹을까요?(도메인 밖, Fallback 기대)

**답변 품질 기준(자체 정의)**: **○ 만족** = 정책 근거로 정확·충실히 답(원문 수치·조건 포함), 도메인 밖은 군더더기 없이 거절·유도 / **△ 애매** = 부분답 + `"어떤 주문을 말씀하시는 건가요?"`식 deflect 꼬리 또는 언어 누출 / **✗ 불만족** = Fallback·환각·무관 답.

### 정량 비교 표

**POST-FIX (QA 템플릿 재균형 + threshold 0.45)** — 현재 코드 상태로 재실행한 값이다. 원본: [`raw/stage2-chunking.txt`](raw/stage2-chunking.txt) **PART N**. (C는 문서<2000토큰이라 청킹이 A와 바이트 동일 → A 결과로 대체, 미재실행.)

| 실험 | chunkSize | minChunkSizeChars | `vector_store` row 수 | 평균 입력 토큰(5턴) | 답변 품질(○/△/✗) |
| --- | --- | --- | --- | --- | --- |
| **A (기준)** | 800 | 350 | **7** (문서당 1청크) | **3912** | **○4 △1 ✗0** |
| B (극소) | 100 | 40 | **49** (문서당 6~8청크) | 3619 | ○2 △3 ✗0 |
| C (크게) | 2000 | 800 | 7 (=A) | 3912 (=A) | ○4 △1 ✗0 (=A) |

질문별 (post-fix): 

| Q | A(800) | B(100) | C(=A) |
| --- | --- | --- | --- |
| ① 기상보상 | ○ | △ 부분+되묻기 | ○ |
| ② 결제취소 | ○ | △ 한줄+되묻기 | ○ |
| ③ 쿠폰중복 | △ 정답+잔존 Fallback 한 줄 | △ **자기모순**(조각남) | △ |
| ④ 배달후환불 | ○ | ○ | ○ |
| ⑤ 도메인 밖 | ○ | ○ | ○ |

> **fix 전(PART I) 대비**: ③ 쿠폰중복이 **✗✗✗(Fallback) → △/△/△** 로 바뀌었다([Fallback 수정](#fallback-과발동-수정--qa-템플릿-재균형) 효과). 표 전체로는 A `○3△1✗1→○4△1✗0`, B `○1△3✗1→○2△3✗0`. **A/B/C 비교 결론은 동일** — A 최적, B는 조각남으로 품질↓(○2, deflect 꼬리). 특히 **B-③은 fallback이 아니라 "자기모순"** 으로 실패: 원문 *"배달비 쿠폰과 함께는 사용 가능"* 을 *"…함께 사용하는 것은 허용되지 않습니다"* 로 뒤집음 — 조각남이 fallback→오답으로 *형태만* 바뀐 것(800 통째 청크에선 안 나옴).

1. **B(100/40) = "문맥 조각남" 확인**: 문서당 6~8청크(총 **49 row**, A의 7배)로 쪼개져, topK=4가 정책의 *일부 조각*만 긁어온다 → 답이 얇아지고 `"어떤 주문을 말씀하시는 건가요?"` deflect 꼬리가 ①②④에 붙어 **○3→○1로 하락**. 역설적으로 평균 토큰은 **더 낮다(3451<3845)** — 큰 문서 1~2개 대신 작은 조각 4개라 주입량이 적기 때문. → **B는 더 싸지만 더 부실**.
2. **C(2000/800) = A와 완전 동일**: 문서가 ~450토큰(<2000)이라 문서당 1청크, row·토큰이 A와 같다. 숙제가 기대한 *"토큰 낭비/유사도 뭉툭"* 은 **이 코퍼스에선 안 나타난다** — 그 효과는 문서가 2000토큰보다 길 때만 발현. (대신 C-②에서 **중국어 누출** — qwen2.5 산발적 언어 드리프트, 청킹 무관.)
3. **③ 쿠폰은 3 config 전부 ✗ Fallback** (PART I 당시): coupon-faq가 어느 설정에서도 주입됐는데(A·C 1청크 통째, B도 중복 조항 조각 포함) 모델이 안 쓴다 → 청킹·검색 무관한 **LLM Fallback 과발동**(상수 교란). 모든 config에서 똑같이 ✗라는 것 자체가 "청크 문제 아님"의 증거. → 이후 **[Fallback 수정(QA 템플릿 재균형)](#fallback-과발동-수정--qa-템플릿-재균형)으로 해결**: 현재 800/0.45에서 **○△○**(재확인 완료).
4. **⑤ 도메인 밖은 3개 모두 입력토큰 3258(=컨텍스트 0건)**: threshold 0.45에서도 무관 문서를 못 넘겨 빈 컨텍스트 → `[금지]`·도메인 규칙으로 거절. 기대대로.

### 실패 관찰 — 정량 지표 (조각남 객관 측정)

위 답변 품질(○/△/✗)은 주관 판정이라, B의 "조각남" 실패를 **숫자로** 보강한다. 원본: [`raw/stage2-chunking.txt`](raw/stage2-chunking.txt) **PART K**.

**① 핵심 사실 커버리지** — Q2 "결제 후 취소"의 핵심 4사실([CREATED/ACCEPTED 전액환불]·[COOKING 불가]·[카드 7영업일]·[배달페이 즉시복원]) 중 답변 포함 개수:

| config | Q2 커버리지 | 이유 |
| --- | --- | --- |
| A(800) | **4/4** | refund-basic 1청크에 전 조항 → 모두 인용 |
| B(100) | **1/4** | CREATED/ACCEPTED만, 나머지 조항 누락 후 deflect ← **조각남** |
| C(2000) | 2/4 | A와 동일 청크지만 모델이 7영업일·복원 누락(+중국어) |

(전체 Q1~Q4 합계는 A 9 / B 8 / C 8 로 근소 — 실패는 *다조항 질문 Q2*에 집중. Q3는 셋 다 0=LLM fallback.)

**② B=100 검색 레벨 조각남** — Q2에서 정답 정책이 8조각으로 흩어져 topK=4 한도를 넘긴다:

```
topK=4 (B=100, Q2 "결제 후 취소"):
  0.585 refund-basic   "조리 시작 이후(COOKING) ..."
  0.568 refund-basic   "조리 시작 전: CREATED/ACCEPTED ..."
  0.567 cancel-policy  "... 즉시 취소 전액 환불 ..."
  0.533 refund-after-delivered  "배달 완료 후 환불 ..."   ← 무관(noise)
→ '카드 7영업일'·'배달페이 즉시복원' 조항은 회수 안 된 다른 조각에 남아 누락 + 무관 문서가 1슬롯 점유.
  (A=800: refund-basic 1청크에 전 조항 포함 → 누락 없음)
```

**③ deflect 꼬리 빈도**(5질문 중 "어떤 주문을 말씀하시는 건가요?"식 되묻기 종결): **A 1 / B 4 / C 1** — B의 부실·회피 경향을 행동 빈도로도 정량 확인.

> 정직한 한계: **C의 기대 실패("토큰 낭비/유사도 뭉툭")는 문서가 짧아(<2000) A와 동일해 정량화할 실패가 없다**(음성 결과). 즉 "큰 chunkSize의 낭비"는 긴 문서 코퍼스에서만 측정 가능.

**결론**: **A(800/350)가 최적** — 정책이 자기완결 1청크로 들어와 답이 충실(○3, Q2 4/4). B는 조각남으로 품질↓(Q2 1/4·되묻기 4회), C는 짧은 문서라 A와 무차별. "1 정책 = 1 청크"가 이 도메인의 sweet spot이며, 설계 결정 1의 검색 레벨 결론(PART D)과 end-to-end로 일치한다.

---

### 실패 관찰 — Fallback 가드 없는 환각 (레이어드 ablation)

> 숙제 지정 실험: `BaedalPrompt`의 `[정책 인용 규칙]`을 임시 주석 처리하고 도메인 밖 질문(`"오늘 점심 뭐 먹을까요?"`)을 재전송. 원본: [`raw/stage2-chunking.txt`](raw/stage2-chunking.txt) **PART L**.

가드를 한 겹씩 벗기며 ×3씩 관찰했다 (가드 후보 4겹: (a)`[규칙]` 도메인밖 안내 · (b)`[금지]` 무관주제 추천금지 · (c)`[정책 인용 규칙]` Fallback · (d)QA 템플릿 빈-컨텍스트 Fallback):

| 조건 | 제거한 가드 | 결과(×3) |
| --- | --- | --- |
| Baseline | — | 거절 ✅ |
| **A1 (숙제 지정)** | (c) `[정책 인용 규칙]`만 | **거절 3/3 (변화 없음)** |
| A2 | (c)+(a)+(b) 도메인밖 줄 | 거절 3/3 (이번엔 (d) QA 템플릿이 막음) |
| **A3** | (c)+(a)+(b)+(d) **가드 전무** | **🔴 환각 3/3 — 메뉴 발명** |

**A3 환각 캡처**: *"현재 메뉴는 다음과 같습니다: 1. 피자: 치즈피자, 햄피자 … 2. 파스타: 바질소스파스타 … 3. 샐러드: 블랙베리샐러드 …"* — **정책 문서에 없는 메뉴를 통째로 지어냄**(검색은 여전히 0건). 복원 후 다시 정상 거절.

→ 핵심: **`[정책 인용 규칙]` 하나만 제거(A1)해선 환각이 안 난다** — 이 프롬프트에선 도메인밖 거절을 `[규칙]`·`[금지]`·QA 템플릿이 **중첩(방어 심층화)** 으로 담당하기 때문. 숙제가 기대한 "규칙 하나 제거 → 환각"은 더 단순한 프롬프트 가정이고, **4겹을 모두 제거(A3)해야 환각이 재현**된다.

### 설계 결정 문서 (2단계)

**1. 배달 정책 도메인에서 가장 적합한 청크 크기는?** → **800 (min 350)**. A/B/C end-to-end + PART D 검색 레벨 근거: 정책 문서가 ~450토큰(572~722자)으로 짧아 800이면 **1 정책 = 1 청크**가 되어 조항이 통째로 검색·주입된다. B(100)는 문서당 6~8조각으로 쪼개져 핵심 조항 누락(Q2 4/4→1/4)·되묻기 빈발(4회), C(2000)는 A와 무차별(짧은 문서). → 800이 "완전성"과 "토큰 효율"의 균형점.

**2. 청크 오버랩을 0으로 바꾸면? 왜 오버랩이 필요한가?** → 사실 Spring AI `TokenTextSplitter`에는 **오버랩 파라미터가 없다(항상 0, 순차 분할)**. "0으로 바꾸는" 게 아니라 **0이 유일한 기본**이라, 비교하려면 커스텀 splitter가 필요하다 → `OverlappingTokenTextSplitter`(`rag.chunk-overlap` 프로퍼티, 기본 0=투명)를 만들어 **chunk=100에서 overlap 0 vs 30 을 정량 비교**했다. 원본: [`raw/stage2-chunking.txt`](raw/stage2-chunking.txt) **PART O**.

`coupon-faq`의 `## 쿠폰 중복 사용` 3조항(할인+배달비 가능 / 할인+할인 불가 / 프로모션)이 chunk=100에서 경계로 쪼개진다:

| 지표 (경계 질의 "프로모션 쿠폰도 다른 쿠폰과 중복 사용 가능?") | overlap=0 | overlap=30 |
| --- | --- | --- |
| 중복 핵심 동시 포함 청크 수(완전성) | **0** (조항이 두 청크로 분리) | **1** (한 청크로 복구) |
| 검색 top-1 cosine | 0.7705 | 0.7749 |
| top-1 청크 내용 | 프로모션 한 줄만(중복 규칙 없음) | 할인+할인 불가 **+** 프로모션(완결) |

→ **오버랩의 가치는 "유사도↑"가 아니라(0.77로 거의 동일) 검색된 top-1 청크의 "자기완결성"** 이다. overlap=0의 top-1은 불완전 조각이라 그 한 청크로는 "할인끼리 중복?"에 답 못 한다 — [PART N의 B-③ 자기모순](#정량-비교-표)(조각남)의 뿌리. overlap=30은 경계 내용을 품어 한 청크로 답 가능.

**단, 우리 기본값 chunk=800에선 오버랩이 무의미**: 정책 문서가 ~450토큰<800이라 문서당 1청크 → 경계 자체가 없다(0이든 30이든 동일). 오버랩은 **문서가 chunkSize보다 길어 쪼개질 때**(chunk=100, 장문 블로그/PDF)에만 효과 → 그땐 chunk를 줄이고 overlap(50~100)을 둬 경계 손실을 막아야 한다. (본 실험이 그 효과를 완전성 0→1로 정량 확인.)

**3. "사용자 리뷰 10만 건"이라면 청크 전략은?**
- **청크 크기**: 리뷰 1건이 평균 수십 토큰이라 chunkSize를 키울 이유가 없다. **리뷰 1건 = 1청크**로 고정(여러 리뷰를 한 벡터에 섞으면 검색 정밀도 붕괴). 긴 리뷰만 200~300토큰으로 분할 + 오버랩.
- **중복 방지**: 정책 7건은 `faqId` 1개로 멱등 처리했지만, 10만 건은 **리뷰 PK(reviewId) + 내용 해시**로 dedup. 같은 리뷰 재적재·중복 작성 방지.
- **재인덱싱 주기**: 전량 재적재는 비현실적. **증분 인덱싱**(신규/수정 리뷰만 큐로 적재) + 임베딩 모델 교체 시에만 전량 재색인. 삭제 리뷰는 tombstone로 비활성화.

**4. "similarityThreshold만으로 환각을 막을 수 있는가?"** → **아니다.** [실패 관찰 ②](#실패-관찰--fallback-가드-없는-환각-레이어드-ablation) A3가 직접 증명: 도메인 밖 질문은 어느 threshold에서도 **검색 0건**(빈 컨텍스트)인데, 가드를 모두 제거하니 모델이 메뉴를 환각했다. **threshold는 "무엇을 검색·주입하느냐"(retrieval)만 통제**하고, 빈/무관 컨텍스트에서 모델이 환각하느냐 거절하느냐는 **프롬프트 가드(Fallback 규칙·`[금지]`·QA 템플릿)** 가 결정한다. 둘은 역할이 다르며 **협업**해야 환각을 막는다(방어 심층화).

### 2단계 체크리스트

| 항목 | 증거 | 결과 |
| --- | --- | --- |
| 3가지 청크 크기 `row 수 / 평균 입력 토큰 / 답변 품질` 비교 표 | [정량 비교 표](#정량-비교-표) post-fix(A 7/3912/○4 · B 49/3619/○2 · C =A) — `stage2-chunking.txt` PART N | ✅ |
| 실험 B 문맥 조각난 `Context:` 블록 + LLM 응답 캡처 | `stage2-chunking.txt` PART K(B=100 topK 조각 4개+noise) + PART I/N(B 응답: 부분답+되묻기·자기모순) | ✅ |
| `[정책 인용 규칙]` 제거 시 환각 사례 캡처 | `stage2-chunking.txt` PART L A3(메뉴 환각 "피자/파스타/샐러드…" ×3) | ✅ |
| 오버랩 필요성 + 리뷰 도메인 청크 감각 | [설계 결정 2·3](#설계-결정-문서-2단계) — 오버랩은 커스텀 splitter로 **정량 검증**(overlap 0 vs 30, 완전성 0→1, `stage2-chunking.txt` PART O) | ✅ |

---

## 3단계: Memory + RAG 동시 적용 + Advisor 순서 실험

Memory와 RAG가 같은 체인에서 각각 무슨 일을 하는지 2턴 대화로 관찰하고, `questionAnswerAdvisor`의 `order(20)→order(5)`로 **RAG를 Memory보다 먼저** 실행시켜 무엇이 깨지는지 본다. 질문: T1 `"주문번호 2024-1234 배달 어디?"` → T2 `"아까 그 주문 환불 돼요?"`. 원본: [`raw/stage3-memory-rag-order.txt`](raw/stage3-memory-rag-order.txt) **PART M**.

### 캡처 (정상 order `memory(10)→rag(20)`, 2턴 DEBUG 원문)

`local` 프로파일 + `SimpleLoggerAdvisor(order 30)`로 떠낸 실제 로그. 전문: [`raw/stage3-memory-rag-order.txt`](raw/stage3-memory-rag-order.txt) PART M.

**■ Turn 1** — `"주문번호 2024-1234 배달 어디?"` → `getDeliveryStatus` Tool 호출, RAG Context 0건(배달 위치 질의라 정책 미검색).

LLM 최종 응답(T1):
```
주문이 배달 중이며, 현재 라이더는 역삼역 사거리 부근에서 배송 중입니다.
예상 도착 시간은 2026년 6월 8일 오전 6시 47분 40초입니다.

배달 상태를 계속 확인하실 수 있습니다. 추가로 궁금한 점이 있으신가요?
```

**■ Turn 2** — `"아까 그 주문 환불 돼요?"`

(1) **Memory가 주입한 이전 USER/ASSISTANT 메시지** (T2 request의 messages 앞부분):
```
UserMessage{content='주문번호 2024-1234 배달 어디?', ... messageType=USER}
AssistantMessage [messageType=ASSISTANT, toolCalls=[], textContent=
  주문이 배달 중이며, 현재 라이더는 역삼역 사거리 부근에서 배송 중입니다.
  예상 도착 시간은 2026년 6월 8일 오전 6시 47분 40초입니다. ...]
```

(2) **QA Advisor가 주입한 Context 블록** (현재 턴 `"아까 그 주문 환불 돼요?"`로 검색 → refund-basic 0.541 / cancel-policy 0.508 / refund-after-delivered 0.485 주입):
```
아래는 사용자 질문과 관련해 검색된 [정책 문서]입니다.
---------------------
# 환불 기본 정책

배달에서 주문 환불은 **주문 상태**와 **사유**에 따라 다르게 처리됩니다.

## 환불 가능 케이스
- **조리 시작 전 취소**: 주문 상태가 CREATED 또는 ACCEPTED인 경우, 고객 요청으로 전액 즉시 취소/환불 가능합니다.
- **음식 누락 / 오배송**: 배달 완료 후에도 사진 등 증빙을 확인하는 조건으로 전액 또는 해당 메뉴 부분 환불이 가능합니다.
- **배달 지연 과도 (60분 이상)**: 예상 시간 기준 60분 이상 지연된 경우 배달비 환불 또는 쿠폰 보상이 가능합니다.

## 환불 불가 또는 제한적 처리 케이스
- **조리 시작 이후 단순 변심**: 조리가 시작된(COOKING) 이후의 단순 변심 취소는 전액 환불이 어려우며, 가게와 조율이 필요합니다.
- **배달 완료 후 24시간 초과**: 배달 완료 후 24시간이 지난 단순 맛 불만족은 환불 대상이 아닙니다. 리뷰로 의견을 남겨 주세요.

## 환불 소요 기간
- 카드 결제: 카드사에 따라 **최대 7영업일**이 소요될 수 있습니다.
- 배달페이 / 머니 충전금: 취소 즉시 복원됩니다.

## 처리 절차
1. 고객센터 또는 앱 내 문의에서 주문번호를 제출합니다.
2. 증빙(사진 등)이 필요한 경우 상담사가 요청합니다.
3. 정책 범위 내 건은 당일 처리, 범위 밖 건은 상담원이 개별 조율합니다.
# 주문 취소 정책

## 자동 취소 가능 조건
앱에서 고객이 직접 취소할 수 있는 조건입니다.
- 주문 상태가 **CREATED** (주문 접수 직후)
- 주문 상태가 **ACCEPTED** (가게가 접수 확인은 했으나 조리 시작 전)
이 상태에서는 앱의 "주문 취소" 버튼으로 즉시 취소되며, 결제 금액은 전액 환불됩니다.
   … (주문 취소 정책 이하 + 배달 완료 후 환불 정책 0.485 가 같은 블록에 연결 주입 — 전문은 raw)
---------------------
지침: ① 근거가 일부라도 있으면 반드시 사용 ② 관련 조항 보이면 섣불리 "못 찾음" 금지 ③ 빈/무관일 때만 Fallback
질문: 아까 그 주문 환불 돼요?
```

(3) **LLM 최종 응답(T2)**:
```
주문 상태와 관련된 정보가 필요합니다. 아까의 주문이 어떤 상태였는지 알려주시면 정책에 따라 답변 드리겠습니다.
현재 상태가 CREATED, ACCEPTED, COOKING, DELIVERING, DELIVERED 중 어느 단계인지 확인해주세요.
```
→ 환불 정책(CREATED/ACCEPTED→전액환불 등)을 근거로 받았으나, **"그 주문(2024-1234)"의 status**가 이력에 없어(T1은 위치만 조회) 모델이 상태를 되묻는다 — Memory·RAG는 각자 동작하지만 *상태 기반 융합*은 모델 몫이라는 한계(설계 결정 3 참조).

### 관찰 기록 표

| 관찰 포인트 | `memory(10) → rag(20)` (정상) | `rag(5) → memory(10)` (고장 시도) |
| --- | --- | --- |
| 2턴 Context에 들어간 정책 카테고리 | refund(환불기본·배달완료후) + cancel(주문취소) | **동일** (refund + cancel) |
| Context 정책이 현재 주문(1234)과 관련 있는가 | ✗ — "환불" 키워드로 *일반* 환불/취소 정책 검색, **orderId 미사용** | ✗ — **동일** (orderId 미사용) |
| LLM 응답의 정확도 (원문 수치 포함) | CREATED/ACCEPTED 전액환불 인용 (+상태 되묻기) | CREATED/ACCEPTED 전액환불 + 24시간 인용 (+상태 되묻기) — **동등** |
| Memory의 이전 USER/ASSISTANT 주입 | ✓ (1234 배달조회 이력) | ✓ **동일** |

### 결론 — order 스왑이 아무것도 깨지 않았다

`order(20)→order(5)`로 바꿔도 **Memory 주입·RAG Context·LLM 응답이 모두 동일**했다(검증 카운트: 이전 메시지 등장·Context 문서 집합·distance 바이트 동일). 이유:

- **Spring AI `QuestionAnswerAdvisor`는 advisor 순서와 무관하게 "현재 턴 user text"(`아까 그 주문 환불 돼요?`)를 임베딩**한다 — memory가 푼 orderId를 검색에 쓰지 않는다.
- **`MessageChatMemoryAdvisor`는 순서와 무관하게 이력을 별도 메시지로 주입**한다.
- 둘은 서로 다른 부분을 건드려 **교환 가능(commute)** → 최종 프롬프트가 같다.

→ 숙제가 기대한 "고장"(order를 바꾸면 검색이 엉뚱해짐)은 **memory가 질의를 재작성하는 설계에서만** 발생한다. 이 구현엔 그 재작성이 없으므로 정상/스왑 차이가 없다. (힌트의 *"그 주문이 1234로 복원되기 전이라 자체로 검색"*은 맞지만, 그건 **정상 order에서도 똑같다** — RAG는 어느 순서든 resolved orderId를 안 쓴다.) 이는 [설계 결정 3](#3-왜-questionansweradvisororder20-인가)·PART F의 검색-레벨 결론을 **실제 order(5) 스왑으로 end-to-end 재확인**한 것이다. order의 실질 가치는 검색이 아니라 **performance(100)가 전체 호출을 측정**하도록 보장하는 데 있다.

> ⚠️ 실험 중 ollama가 재차 wedge(생성 무응답) → `brew services restart` + 285s cold-load 후 모델 상주(keep_alive 30m)로 재개. 장시간 반복 재기동發 인프라 불안정이며 코드/순서와 무관.

### 설계 결정 문서 (3단계)

**1. 왜 Memory Advisor가 RAG보다 먼저 실행되어야 하는가? (프롬프트 조립 순서 관점)**

교과서적 그림: `memory(10)`가 먼저 이전 턴(USER `2024-1234 배달 어디?` + ASSISTANT 응답)을 프롬프트에 조립 → `rag(20)`가 "복원된 질문(그 주문=2024-1234의 환불)"으로 검색 → 정확한 정책을 가져온다. 그래서 Memory가 먼저여야 한다.

**그러나 정량 실험([PART M](raw/stage3-memory-rag-order.txt))이 이 메커니즘이 본 구현에선 작동하지 않음을 보였다**: `order(20)↔order(5)` 스왑에서 turn2 RAG Context가 **바이트까지 동일**(환불기본 0.541·주문취소 0.508·배달완료후 0.485). 이유 — Spring AI `QuestionAnswerAdvisor`는 *조립된 프롬프트*가 아니라 **"현재 턴 UserMessage 텍스트"만** 임베딩하고, `MessageChatMemoryAdvisor`는 그 UserMessage를 *재작성하지 않고* 별도 history 메시지로만 주입한다. 즉 Memory가 먼저 와도 현재 UserMessage는 여전히 `"아까 그 주문 환불 돼요?"`(orderId 미복원)이고 RAG는 그걸 임베딩한다 → **"복원된 질문"이 RAG에 전달되지 않는다.**

→ 따라서 이 구현에서 `memory(10)→rag(20)`을 유지하는 **실질 근거는 "검색 개선"이 아니라**:
- (a) **논리적 프롬프트 조립** — LLM이 `[이력 → 검색 컨텍스트 → 현재 질문]` 의 일관된 메시지 순서로 읽도록.
- (b) **`performance(100)`가 최종 LLM 호출 전체를 측정**하도록 가장 바깥을 보장(rag보다 안쪽이면 주입 전 토큰을 재 측정이 틀어짐).

"복원된 질문으로 검색"이라는 이득을 실제로 얻으려면 **별도 query-rewriting 단계**(이력+현재를 standalone 질의로 압축 후 임베딩)가 필요하다 — 이 구현엔 없으므로 order의 검색상 효과는 0이다.

**2. 반대 순서(RAG 먼저)가 더 나은 상황이 존재하는가? (Round 5 Guardrail 예고)**

있다 — **PII/보안 관점**. 만약 RAG가 *조립된 프롬프트(이력 포함)* 를 임베딩하는 설계라면, 이전 턴의 PII(주소·전화·카드번호 등)가 **임베딩 모델 호출·벡터 DB 질의·DEBUG 로그로 흘러가 유출**될 수 있다. 이 경우 **RAG를 먼저** 실행해 "현재의 깨끗한 발화만" 임베딩하고 PII 포함 이력은 뒤에 주입하는 편이 안전하다.

정량적으로 우리 구현은 **이미 이 속성을 가진다**: 스왑 실험에서 RAG는 어느 순서든 현재 턴 텍스트만 임베딩했으므로(turn1 `"배달 어디?"`의 `qa_retrieved=[]`, turn2는 `"환불"`만으로 검색), **이력의 PII는 어떤 순서에서도 임베더·벡터스토어에 도달하지 않는다.** 즉 "RAG 먼저가 PII에 더 안전"하다는 통념은 *RAG가 이력을 임베딩하는 설계* 에서만 유효하고, 본 설계는 그 위험 자체가 없다.

→ **Round 5 Guardrail 예고**: PII 마스킹/Guardrail advisor를 넣는다면 **임베딩·검색·로깅보다 먼저** 실행돼 민감정보가 벡터스토어·로그·외부 임베딩 호출에 닿기 전에 제거해야 한다. **advisor order가 곧 보안 경계**를 정의한다.

**3. 실험 후 원래 순서 복원** → `RagConfig.questionAnswerAdvisor`의 `order`를 `5`→**`20`** 으로 복원 완료(현재 코드 상태).

### 3단계 체크리스트

| 항목 | 증거 | 결과 |
| --- | --- | --- |
| 2턴 대화의 Memory 주입 / RAG 주입 / LLM 응답 3요소 캡처 | [캡처(2턴 DEBUG 원문)](#3단계-memory--rag-동시-적용--advisor-순서-실험) — Memory 메시지·Context 블록·응답 전문 / `stage3-memory-rag-order.txt` PART M+부록 | ✅ |
| Advisor 순서 스왑 "무엇이 깨졌는지" 표 | [관찰 기록 표](#관찰-기록-표) (결과: **안 깨짐 — 정상=스왑 동일**, 이유 규명) | ✅ |
| "왜 Memory 먼저"를 프롬프트 조립 순서 관점에서 설명 | 위 [설계 결정 (3단계) 1](#설계-결정-문서-3단계) | ✅ |
| order(20) 복원 | `RagConfig` `.order(20)` 복원 | ✅ |

---

## 선택 과제 4.4 — RetrievalAugmentationAdvisor (RAA) 심화

**동기**: stock `QuestionAnswerAdvisor`는 `getUserMessage().getText()`(현재 턴 텍스트)만 임베딩해 **advisor 순서가 검색에 영원히 무관**했다([설계 결정 3](#3-왜-questionansweradvisororder20-인가)·PART F·M). "순서에 따른 실질 차이"를 보려면 **이력으로 질의를 재작성**하는 단계가 필요 → `RetrievalAugmentationAdvisor`(RAA)로 검증. 원본: [`raw/stage3-memory-rag-order.txt`](raw/stage3-memory-rag-order.txt) **PART P**.

**구현** (`RagAdvancedConfig`, `@Profile("raa")` — 기본 off): `spring-ai-rag` 의존성 추가 +
```
RetrievalAugmentationAdvisor =
  CompressionQueryTransformer(chatClientBuilder)      # 이력+후속질문 → standalone 질의로 압축
  VectorStoreDocumentRetriever(threshold 0.45, topK 4)
  ContextualQueryAugmenter(allowEmptyContext=…)
```
`raa` 프로파일이면 `AssistantChatClientConfig`에서 RAA가 `QuestionAnswerAdvisor`를 대체한다(`memory(10) → RAA(20) → perf(100)`).

**실험 (시나리오 5)**

- **`CompressionQueryTransformer` 동작 확인** — DEBUG: *"Compressing conversation history and follow-up query into a standalone query"*. 이력을 쓰는 압축 단계 = **order-sensitive 메커니즘**(이력이 있으려면 memory가 먼저 실행돼야 함). stock QA엔 없던 단계 → 이게 order를 의미있게 만드는 핵심.

| 조건 | T1 `"2024-1234 배달 어디?"` | T2 `"아까 그 주문 환불 돼요?"` |
| --- | --- | --- |
| `allowEmptyContext=false` | ❌ 거절 (getDeliveryStatus 미호출 — **tool 경로 깨짐**) | 검색 0건 → *"Empty context is not allowed"* → 강제 거절 |
| `allowEmptyContext=true` | ✅ 정상 (배달 상태 응답, tool 복원) | 검색 **성공**(refund-basic 0.57·cancel 0.55·refund-after 0.52) 但 **중국어 드리프트** + 여전히 주문번호 되물음(융합 실패) |

**결론**
- **"order가 검색에 의미를 가지려면 RAA+QueryTransformer가 필요"는 사실로 확인**됐다 — CompressionQueryTransformer가 *이력+후속질문 → standalone 질의* 압축을 실제 수행(이력 사용=order 민감). stock QA엔 이 단계가 없어 order가 무관했던 것.
- **그러나 이 도메인/모델에선 RAA가 "제대로 된 해법"이 못 된다**: (a) `allowEmptyContext=false`면 정책 미검색 질의(배달 위치 등 **tool 경로**)를 강제 거절 → `true` 필수, (b) 압축이 LLM 콜을 추가해 턴당 ~2.5분 + ollama wedge 빈발, (c) **검색이 성공해도 시나리오 5 "융합"은 여전히 실패**(주문번호 되물음) + qwen2.5 중국어 드리프트. → 융합 실패는 order/검색이 아니라 **모델 추론 한계**임을 RAA로도 재확인.
- 정리: order를 의미있게 만드는 건 RAA(확정)이지만 **융합·안정성까지 좋아지진 않는다.** 이 도메인엔 stock `QuestionAnswerAdvisor`(order 무관·단순·안정)가 실용적으로 더 낫고, RAA는 *심화*답게 튜닝 부담(allowEmptyContext·압축 질의 품질·tool 라우팅)이 크다 — 숙제가 "선택 과제"로 둔 이유와 부합.
- **order 스왑 실측 — RAA에선 order가 실제로 검색을 바꾼다** (CompressionQueryTransformer 압축 LLM 콜 캡처):

| order | T2 압축(재작성) 질의 | 검색 결과 |
| --- | --- | --- |
| **`memory(10)→RAA(20)`** | **"아까 주문번호 2024-1234의 배달 상태와 환불 여부를 알려주세요"** ← 이력으로 **2024-1234 복원** | refund 0.556·cancel 0.537·refund-after 0.502 (3건) |
| **`RAA(5)→memory(10)`** | **"Did my recent order get refunded?"** ← 이력 부재로 **미복원**(generic + 영어 드리프트) | cancel 0.477·refund 0.463 (2건, 점수↓) |

→ **stock QA에선 order 무관(PART M)이었지만, RAA+CompressionQueryTransformer를 넣으면 "memory가 먼저여야 이력을 읽어 질의를 복원" → order가 비로소 검색 결과를 바꾼다(실증 완료, PART P 부록).** 숙제가 말한 "고장"(RAA-first면 '그 주문'을 못 풀어 엉뚱하게 검색)이 그대로 재현됨. 단 두 order 모두 최종 응답의 *융합*(상태 기반 답)은 여전히 실패 → 융합은 order가 아니라 모델 한계(불변).

> 코드는 `@Profile("raa")`로 게이트되어 **기본 실행(local)에는 영향 없음** — 평소엔 `QuestionAnswerAdvisor` 경로로 동작한다.

---

## 체크리스트 증명

| 체크 항목 | 증거 | 결과 |
| --- | --- | --- |
| `bootRun` 정상 실행 + `KnowledgeLoader` **신규 7건** | [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) **PART C §1** (최초기동 발췌: `적재 완료 ×7`, `신규 7건 / 스킵 0건`) | ✅ |
| 시나리오 5종 응답 본문 + `Context:` 주입 블록 발췌 | **PART A** 전량 (`Context information is below ---` 블록 포함) | ✅ |
| 시나리오 4 — `[금지]`로 **전화번호 미노출** 증명 | **PART A** 시나리오 4 (R1·R2·R3): 3회 모두 거절, 전화번호 문자열 0회 등장 / `qa_retrieved_documents=[]` | ✅ |
| 시나리오 5 — "아까 그 주문"=2024-1234 복원 + 환불정책 검색 | **PART A** 시나리오 5 세션 Memory 덤프(`memo-rag-r1/r2/r3`)에 2024-1234 보존 + Q2 Context에 환불·취소 정책 주입(0.54/0.51) | ✅(인프라) / △(모델 융합) |
| 재기동 후 **스킵 7건** (중복 방지) | [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) **PART C §2** (재기동 발췌: `이미 적재됨 ×7`, `신규 0건 / 스킵 7건`) | ✅ |
| 설계 결정 4종 "왜?" | 위 [설계 결정 문서](#설계-결정-문서) 1~4 | ✅ |

### `vector_store` 분포 (PgVector 직접 조회)

```
 rows |    category
------+----------------
    1 | account          (privacy)
    1 | cancel           (cancel-policy)
    1 | coupon           (coupon-faq)
    2 | delivery-delay   (delay-compensation, weather-delay)
    2 | refund           (refund-basic, refund-after-delivered)
(총 7 rows, 문서 7건 = 청크 7개)
```

---

## Observability 함정 — DEBUG 로그에 `Context:`를 *보이게* 만들기

`SimpleLoggerAdvisor`는 원래 `order(20)`이었는데, 이는 **`QuestionAnswerAdvisor`와 동순위(20)** 라
주입 순서가 보장되지 않아 **RAG가 붙인 `Context:` 블록이 request 로그에서 누락**됐다(실측 확인 —
로그의 `UserMessage`가 RAG 주입 전 원문 질의만 표시).

→ **`order(30)`으로 상향**(memory 10 · RAG 20 *이후* 로깅). 수정 후 request 로그에
`Context information is below ---------------------` + 정책 문서 원문 + `qa_retrieved_documents=[…]` 가
모두 찍혀, 본 검증의 Context 발췌가 가능해졌다. (`ChatClientConfig.simpleLoggerAdvisor`)

> 교훈: advisor의 `order`는 *실행 순서*만이 아니라 **무엇이 로그에 보이는가**까지 결정한다.
> 관측 대상(주입된 컨텍스트)보다 로거의 order가 작으면 "주입 전"만 찍혀 디버깅이 헛돈다.

---

## 실행 환경 메모 (재현 시 주의)

- **JDK 17 필수**: 기본 `JAVA_HOME`이 JDK 8이면 `gradlew`가 실패. `JAVA_HOME=…/corretto-17 ./gradlew bootRun --args='--spring.profiles.active=local'`.
- **`local` 프로파일**: `SimpleLoggerAdvisor`(DEBUG 본문 로깅)는 `@Profile({"local","dev"})`. Context 발췌를 보려면 이 프로파일로 기동.
- **Ollama 자원 압박 주의**: 검증 중 시스템 메모리 8%·스와핑 + 무관 컨테이너의 CPU 폭주로 ollama가 멎어 초기 호출이 500(`Connection prematurely closed`)·109s 지연. 자원 회복 후 콜당 ~40~70s로 안정화. 임베딩 모델(qwen3-embedding)과 채팅 모델(qwen2.5)이 동시에 로드되므로 메모리 여유 필요.
- 콜당 지연(perf): 단일 RAG 콜 ~40~70s, 시나리오 5-Q1(Tool+이력) ~47~59s. 18콜 총 ~25분.

---

## Raw 로그

raw는 **단계별 3개 파일**로 분리되어 있다.

| 파일 | 내용 (PART) |
| --- | --- |
| [`raw/scenarios-1to5.txt`](raw/scenarios-1to5.txt) | **1단계 + 공통 설계결정 ablation** — **A** 시나리오 5종×3회(응답·Context 발췌·perf·Memory 덤프·vector_store 분포) / **B** Top-K 코사인 유사도 / **C** 시드 `신규 7건`+재기동 `스킵 7건` + pgvector 분포 / **E** Top-K ablation(K=1/4/10×threshold) / **G** threshold ablation(0.30~0.70 → 0.45) / **H** threshold 0.45 end-to-end + 0.5 비교 / **J** Fallback 과발동 수정(QA 템플릿 재균형, 1단계 보강) |
| [`raw/stage2-chunking.txt`](raw/stage2-chunking.txt) | **2단계 청킹** — **D** chunk ablation(검색 레벨) / **I** 청킹 end-to-end A/B/C(fix 전) / **K** 실패 관찰 정량(조각남·deflect) / **L** Fallback 가드 없는 환각(레이어드 ablation A1/A2/A3) / **N** 청킹 end-to-end 재실행(POST-FIX) / **O** 청크 오버랩 실험(커스텀 splitter, overlap 0 vs 30 → 완전성 0→1) |
| [`raw/stage3-memory-rag-order.txt`](raw/stage3-memory-rag-order.txt) | **3단계 Memory+RAG+순서** — **F** Advisor order 증거(검색 불변성·재작성 역효과·가시성) / **M** Memory+RAG 동시적용 + order 20 vs 5 스왑 실험(정상/스왑 동일) / **P** [선택 4.4] RAA 심화(CompressionQueryTransformer·allowEmptyContext·중국어 드리프트) |
