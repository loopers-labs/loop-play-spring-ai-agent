# loop-play-spring-ai-agent

Spring AI 기반 배달 상담 에이전트 학습용 스타터 코드입니다.

루퍼스 부트캠프 "Spring AI 배달 상담 에이전트" 6주 과정 — Week 1 미션.
`ChatClient`, System Prompt, Structured Output, Streaming, Observability 개념을 실습합니다.

## 빠른 시작

```bash
./gradlew bootRun
```

---

## 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/support` | 배달 상담 1차 트리아지 |
| POST | `/api/v1/chat/stream` | SSE 스트리밍 응답 |
| POST | `/api/v1/prompt-lab` | 프롬프트 정량 비교 실험 |

---

## 1주차  

### 시나리오 테스트

배달 위치 문의 · 취소 환불 · 음식 손상 — 3종 시나리오를 실제 API로 호출하고 응답을 검토했습니다.

- [support-triage.http](src/main/resources/evals/support-triage.http) — 요청 파일 (IntelliJ HTTP Client)
- [support-triage-responses.md](src/main/resources/evals/support-triage-responses.md) — 응답 JSON · 금지 규칙 준수 검토

### 실험 리포트

- [temperature-report.md](docs/week1/stage2/temperature-report.md) — temperature 0.0 / 0.3 / 0.7 sweep 결과 및 0.3 선택 근거
- [structured-prompt-comparison-report.md](docs/week1/stage2/structured-prompt-comparison-report.md) — 단순 vs 구조화 프롬프트 비교 결과
- [prohibition-ablation-report.md](docs/week1/stage2/prohibition-ablation-report.md) — [금지] 섹션 제거 시 공격 시나리오 응답 비교

### 설계 결정

- [design-decision-report.md](docs/week1/stage1/design-decision-report.md) — SYSTEM_PROMPT 금지 규칙 · Category enum · 추가 필드 선택 근거

### SSE 스트리밍

- [compare-sync-vs-streaming.md](docs/week1/stage3/compare-sync-vs-streaming.md) — 동기 vs 스트리밍 TTFT 측정 결과
- [streaming-vs-structured-output.md](docs/week1/stage3/streaming-vs-structured-output.md) — Structured Output에 스트리밍을 쓰면 안 되는 이유
- [streaming-frontend-changes.md](docs/week1/stage3/streaming-frontend-changes.md) — Streaming을 적용할 때 프론트엔드 아키텍처 변화 (상태 관리, 에러 처리, 인프라)

### 성능 로깅 (PerformanceLoggingAdvisor)

- [llm-prompt-observation.md](docs/week1/stage4/llm-prompt-observation.md) — 실제 LLM 전달 프롬프트 전문 · 입력 토큰 분포 · System Prompt 중복 분석

---

### 프로덕션 배포 시 예상 사고

실험 결과를 바탕으로 이 에이전트를 그대로 프로덕션에 배포하면 발생할 수 있는 문제다.

**1. 취소 문의에서 주문번호를 요청하지 않음**
취소·환불 요청 시 `neededInfo`가 빈 배열로 반환되는 프롬프트 결함이 모든 실험에서 재현됐다. 주문번호 없이 취소 처리를 시도하면 잘못된 주문이 취소될 수 있다.neededInfo 처럼 **선택 요소가 많은 필드는 
LLM이 누락할 가능성이 높다.**

**2. 외부 공개 시사 시 즉시 에스컬레이션 실패**
`"인터넷에 올릴 거야"` 같은 발언에 금지 규칙이 없으면 AI가 일반 상담 흐름으로 진입한다. **사용자의 가능한 모든 메세지를 파싱해서 regex 금지 규칙을 작성**해야 하는데 현실적으로 불가능하다.

**3. temperature가 올라가면 한자 혼입으로 응답 파싱 실패**
`nextAction` 또는 `neededInfo`에 중국어 한자가 섞이면 서버 측 JSON 파싱 또는 클라이언트 분기 로직이 실패한다. LLM이 한자 혼입을 일으키는 패턴을 분석해서 금지 규칙으로 차단해야 하는데 필드가 
늘어날수록 관리해야 할 패턴이 많아진다.

---

### 내가 배운 것

- **프롬프트 구조가 언어와 형식을 결정한다.** "당신은 배달 고객 상담 AI입니다" 한 줄짜리 프롬프트는 응답 언어를 지정하지 않으면 중국어·영어로 붕괴했다. JSON 형식도 나오지 않았다. 구조화 프롬프트(역할/규칙/금지/응답형식)가 있어야 안정적인 출력이 가능하다는 것을 실측으로 확인했다.
- **같은 필드라도 의사결정 구조가 다르면 temperature 민감도가 다르다.** `nextAction`은 선택지 중 하나를 고르는 단일 결정이라 temperature가 올라가도 큰 변화가 없었다. `neededInfo`는 독립적인 수집 항목이 여러 개 있는 구조여서 temperature가 높아질수록 누락이 늘었다. 출력 필드의 구조를 설계할 때 이 차이를 고려해야 한다.
- **키워드 기반 평가는 문맥을 읽지 못한다.** `"전화번호는 고객님에게 제공되지 않습니다"` — 개인정보를 거절한 올바른 응답인데 "전화번호는" 키워드가 포함됐다는 이유만으로 위반으로 판정됐다. 자동 평가 지표를 설계할 때 오탐을 항상 수동으로 확인해야 한다.

---

### 의문점

- **neededInfo 누락을 프롬프트로 해결할 수 있는가?** 취소·환불 케이스에서 주문번호를 요청하지 않는 결함이 모든 실험에서 재현됐다. 프롬프트에 규칙을 추가하면 해결될 것 같지만, 얼마나 명시적으로 써야 LLM이 안정적으로 따르는지 감이 없다.
- **키워드 평가 대신 LLM-as-judge를 쓰면 오탐이 줄어드는가?** 이번에 문맥을 읽지 못하는 키워드 평가의 한계를 직접 겪었다. LLM-as-judge라고 LLM이 응답을 평가하는 방식을 적용하면 되는 것인지 궁금하다.
- **골든셋 기반 평가에서 예측값을 미리 정의하는 방식이 LLM 평가에 적합한가?** 기대 출력을 사전에 정해두고 비교하는 방식에서는 temperature 0.0이 가장 좋게 나왔다. 그러나 LLM 응답은 정답이 하나가 아닌 경우가 많아서, 예측값과 다르다고 틀린 것으로 보는 평가 방식이 맞는지 의문이다.

---

## 2주차 : Tool Calling 검증 및 실험

### 1단계
- [01_tool_calling_verification.md](docs/week2/stage1/01_tool_calling_verification.md) — `/api/v1/assistant`시나리오 5종 curl로 호출하고 응답·서버 Tool 로그 검증
- [02_order_detail_view_design.md](docs/week2/stage1/02_order_detail_view_design.md)— `OrderDetailView`가 내부 `Order`에서 의도적으로 
  제외한 필드와 그 이유
- [03_tool_description_language_report.md](docs/week2/stage1/03_tool_description_language_report.md) — `@Tool` description 한국어 vs 영어 실험. 
- [04_order_tools_class_design.md](docs/week2/stage1/04_order_tools_class_design.md) - OrderTools 클래스 분리 기준
 
 
### 2단계 
- [06_cancel_order_idempotency.md](docs/week2/stage2/06_cancel_order_idempotency.md) — `cancelOrder`의 4가지 outcome 발생 검증과 같은 주문번호 2회 취소 멱등 흐름 관찰. 
- [07_idempotency_removal_failure.md](docs/week2/stage2/07_idempotency_removal_failure.md) — 멱등성 분기 제거 관찰 실험.
- [08_outcome_enum_design.md](docs/week2/stage2/08_outcome_enum_design.md) — `CancelOrderResult.Outcome` 4값 설계 근거와 멱등성의 세 가지 정책

### 3단계
- [10_tool_description_experiment.md](docs/week2/stage3/10_tool_description_experiment.md) — `@Tool description`이 LLM에게 보여지는 유일한 API 문서라는 가설 검증 실험. 
- [11_tool-description-drift-prevention.md](docs/week2/stage3/11_tool-description-drift-prevention.md) — `@Tool
(description)` 오염, 프로덕션에서 어떻게 막을 것인가

### 4단계
- [12_tool_roundtrip_log_record.md](docs/week2/stage3/12_tool_roundtrip_log_record.md) — Tool 왕복 로그 실측 기록. Tool 호출 여부 입력 토큰 비교

### 내가 배운 것
- 리포트 template을 마련 해두면 좋겠다. 
  - 실험 설계 · 결과 · 결론을 일관된 형식으로 작성할 수 있는 리포트 템플릿을 만들어두면 좋겠다.
  - 그리고 내용이 너무 많은 것은 아닌지, 읽는 사람이 핵심을 쉽게 파악할 수 있는 구조인지도 고민해봐야겠다.
- 가설/판정 template을 마련 해두면 좋다.
  - 실험 설계에서 가설과 판정 기준을 명확히 하는 것이 중요하다. 그래야 자동화하기 좋다. 
  - 실험을 재실행해볼 수 있게 하는 것이 중요하다. 프롬프트는 계속 변하기 때문에 재실험을 진행해야 하는 경우가 많다. 
- 커밋도 자동화 
  - working 브랜치에서 작업하고 제출 시 해당 브랜치로 커밋하는데, 커밋 기록과 메세지를 정리하기 위한 템플릿 마련이 필요하다. 
  
### 의문점
- System prompt도 structured한가?
  - 지금은 문자열 형태인데 시스템 프롬프트를 구조화된 형식으로도 가능한지 확인하고 싶다. 
  - System prompt와 tool description이 `[라벨] 내용` 형태로 쓰여 있다면, 이것 또한 structured하게 만들면 좋지 않을까?
- 다른 분들은 가설/검증의 실험을 어떻게 자동화 하셨는지 궁금합니다. 
  - 그 과정에서 어떤 skills를 사용하는걸까?
  - 저는 가설마다 일일이 과정 MD 파일을 만들어서 실행했습니다. 이게 최선일까? 
- LLM 모델 특징 파악도 중요한가?
  - 가설/검증 실험을 할 때, 모델의 특징을 파악하는 것도 중요한 것 같다.고 판단했는데 정말 이런지 궁금하다. 만약 맞다면 모델 변경시 가설/검증 실험을 다시 해야할까? 모델 변경이 잦다면 가설/검증 실험도 자동화해야할까? 
---

## 3주차 : Chat Memory

### 1단계
- [01_memory_verification.md](docs/week3/stage1/01_memory_verification_report.md) — Chat Memory 멀티턴·세션 시나리오 5종 검증(동작 축 + 내용 
  축 2축)
- [02_design-decision-report.md](docs/week3/stage1/02_design-decision-report.md) — Repository/ChatMemory/Advisor 3레이어 및 
  `MAX_MESSAGES=20` 설계 근거

### 2단계
- [01_memory_size_report.md](docs/week3/stage2/01_memory_size_report.md) —  Memory 윈도우 크기 20 vs 2 vs 무제한 실험. 입력 토큰·응답 지연·대명사 해결 정확도 비교

### 3단계
- [01_jdbc_persistence_report.md](docs/week3/stage3/01_jdbc_persistence_report.md) — InMemory → JDBC(H2) 전환 후 5시나리오 재검증. 세션 분리·멀티턴 기억 유지 확인
- [02_restart_persistence_report.md](docs/week3/stage3/02_restart_persistence_report.md) — 저장소 3종(InMemory·h2:mem·h2:file) 재시작 영속성 비교. "JDBC ≠ 영속" 실측
- [03_storage_design_decision.md](docs/week3/stage3/03_storage_design_decision.md) — InMemory vs JDBC 의사결정 트리, 운영 DB(PostgreSQL) 선택 근거, 비기능 요구사항

### 4단계
- [01_memory_token_growth_report.md](docs/week3/stage4/01_memory_token_growth_report.md) — 10턴 입력 토큰 증가(약 1.42배)와 Memory 조립 시점 주입 증명



### 실무에서 Memory를 개발한다면 고려할 점
 **실제 서비스에 ChatMemory를 얹는 작업**으로 본다면, 무엇을 미리 결정·설계해야 하는지의 관점으로 정리했다.

#### 1. 윈도우 크기는 비용과 회상 요구를 함께 보고 정한다
- **"도메인 평균 턴 수 × 2"에서 출발해 입력 토큰 관측으로 보정**하되(너무 작으면 전역 회상이 깨지고 무제한이면 토큰·지연 폭발), 짧은 세션은 윈도우로, 장기·고객 회상은 요약/RAG로 분리한다.

>**RAG** : LLM에게 답하게 하기 전에 **관련 있는 정보를 외부 저장소에서 검색해 프롬프트에 끼워 넣어** 주는 방식. "LLM이 다 기억하게" 하는 게 아니라 "필요할 때 찾아서 보여주는" 구조.

#### 2. 메모리에 무엇을 저장할지
- **기본은 USER·ASSISTANT만 저장**(토큰 깨끗·예측 쉬움). tool 결과까지 넣으면 회상으로 재호출은 줄지만 토큰을 빠르게 먹고 옛 값을 회상(stale)할 위험이 있다. 꼭 필요한 값만 원문 대신 
  **요약해서** 넣는다.

#### 2. 대화는 곧 개인정보라는 전제로 다룬다
- **Memory 저장소(외부 DB)**에 대화가 평문으로 쌓이므로 저장 시암호화·접근권한, 보존·파기 규칙, 조회용 인덱스로 다룬다.
- 한 줄 요약: **저장할 땐 보호하고, 쌓이면 정리하고(조회용 인덱스), 오래되면 비운다.**

#### 3. tool 재호출 vs 회상 — 무엇이 더 나은지
- **잘 안 변하는 값(메뉴·내역)은 회상**(빠르고 저렴), **자주 변하는 값(배달 위치·상태)은 재호출**(항상 최신)로 가르는 게 출발점.

#### 4. 평가는 재실행 가능하게 만든다.
- 시나리오·판정 기준을 코드화해 **프롬프트·모델이 바뀌어도 N회 반복으로 재실행**할 수 있게 둔다(비결정성·회귀 대비).

**내가 한 방식** — 가설·판정 기준을 [cases.json](docs/week3/stage1/memory_verification/cases.json)에 선언하고, [run 스크립트]
(docs/verification_memory/run_memory_scenarios.sh)로 실행, [evaluate.py](docs/verification_memory/evaluate_memory.py)로 pass/fail을 
코드 판정한다. 프롬프트나 모델이 바뀌면 같은 cases로 재실행만 하면 된다.
```json
{ "message": "그거 언제 도착해요?",
  "hypothesis": "Memory의 2024-1234로 getDeliveryStatus 재호출",
  "expected": { "log_tool": "getDeliveryStatus(orderId=2024-1234", "response_match": "역삼역 사거리" } }
```

#### 5. 세션 식별 — 실무에서는 어떻게 하나

*세션 식별로 할까, 고객 식별로 할까 (설계 고민)*
- 양자택일이 아니라 계층으로 나눈다 — **권한·장기 이력은 고객 ID(인증 주체)로, 지금 대화 맥락은 세션 ID(conversationId)로**. 고객 ID를 그대로 conversationId로 쓰면 무한 누적·세션 간 오염으로 직행하므로, 장기 회상은 RAG로 분리한다.

> RAG = 다 기억시키는 게 아니라 **질문과 관련된 과거 조각만 검색해 프롬프트에 끼워 넣는** 방식(벡터 검색). 현재 대화는 ChatMemory, 장기·고객 회상은 RAG가 맡는 보완 관계.

---
### 의문점

- **무엇을 개발할지 알 수 있었던 건 제공된 코드 조각 덕이 컸다.** 만약 혼자 처음부터 했다면 이걸 어떻게 떠올렸을까 하는 고민이 남는다.
  - **분해** — 어떤 일을 어떤 단위로 나눠야 할까?
  - **탐색** — 무엇이 이미 있는지(기능·라이브러리·패턴) 어떤 방식으로 찾아야 할까?
  - **검증** — 내가 잡은 방향이 맞는지 어떻게 확인할 수 있을까?

---

### 다음 주차에 기대하는 것 — RAG (Round 4)

3주차에서 ChatMemory의 한계(윈도우 밖 회상 불가, 무제한이면 토큰 폭발)를 직접 겪고 "장기·고객 단위 회상은 RAG로 분리"라는 결론에 닿았다. 4주차에서 RAG를 붙여보며 다음 세 가지를 알아가고 싶다.

- **ChatMemory ↔ RAG 경계** — "지금 대화"는 윈도우, "과거 회상"은 RAG로 잘 나뉘는지
- **벡터 검색 정확도** — 다르게 물어도 관련 조각을 찾는지, 엉뚱한 걸 끌어오지 않는지
- **무엇을 벡터에 저장할지** — 대화를 어떻게 조각내고 무엇을 임베딩해야 잘 검색되는지

---

## 4주차 : RAG (Retrieval-Augmented Generation)

### 1단계
- [01_rag_5scenario_report.md](docs/week4/stage1/01_rag_5scenario_report.md) — `QuestionAnswerAdvisor`(order 20) RAG 5종 시나리오 end-to-end 검증. 검색 축(Top-K 주입)+내용 축(Context 인용) 2축 평가
- [02_design-decision.md](docs/week4/stage1/02_design-decision.md) — `RagConfig` 핵심 파라미터(Top-K·SIMILARITY_THRESHOLD·chunkSize 등) 선택 근거

### 2단계
- [01_chunksize_comparison_report.md](docs/week4/stage2/01_chunksize_comparison_report.md) — chunkSize A/B/C(800/100/2000) 비교. 검색 품질·평균 입력 토큰·청크 수 정량 비교
- [02_citation_rule_ablation_report.md](docs/week4/stage2/02_citation_rule_ablation_report.md) — `[정책 인용 규칙]` 주석 처리 ablation. threshold가 무관 문서를 걸러도 규칙 없이는 범위 이탈 → 환각 방지엔 검색+생성 두 가드가 필요

### 설계 결정
- [03_design_decision.md](docs/week4/stage2/03_design_decision.md) — 청크 크기(800)·오버랩 필요성·대규모 리뷰 인덱싱·similarityThreshold vs 환각 4문항 설계 결정

### 3단계
- [01_advisor_order_report.md](docs/week4/stage3/01_advisor_order_report.md) — Advisor 순서 Memory(10)→RAG(20) vs RAG(5)→Memory(10) 고장 실험. 정상 순서의 진짜 이유는 "검색어 개선"이 아니라 "기억 보존"(RAG가 먼저면 보일러플레이트가 Memory를 오염)

### 4단계
- [01_observability_report.md](docs/week4/stage4/01_observability_report.md) — RAG 주입 토큰 비용 관측(3조건 대조). RAG가 입력 토큰 +902(약 +30%), 빈 Memory는 비용 0

---

### 실무에서 RAG를 개발한다면 고려할 점
 **실제 서비스에 RAG를 얹는 작업**으로 본다면, 무엇을 미리 결정·설계해야 하는지 6가지로 요약했다.

1. **청킹은 문서 구조에 맞춰 정한다** — 너무 작으면 문맥이 잘리고 너무 크면 유사도가 희석된다. 고정 길이보다 자연 경계(문단·조항·표)를 살리고, 경계 손실은 overlap으로 보완한다.
2. **검색 품질은 threshold·Top-K·검색 방식으로 조율한다** 
- threshold로 무관 문서를 거르고 Top-K로 정확도와 비용을 균형 잡는다. 
- **임계값(컷오프)은 감으로 정하지 말고 점수 분포로 정한다** — 정답이 코퍼스에 있는 질문(통과 대상)과 도메인 밖 질문(차단 대상)이 실제로 받는 유사도 점수를 모아 보면, 정답은 높게·무관은 낮게 뭉친다. 그 두 분포 사이의 빈 구간에 경계를 두면, 조건부 정답은 통과시키면서 도메인 밖은 걸러내는 값을 데이터로 잡을 수 있다.
3. **환각 방지는 검색·생성 두 겹 가드로 설계한다** — 검색(threshold)과 생성(근거 안에서만 답·인용·근거 없으면 거절)을 함께 건다. 단 거절 규칙을 중복하면 정답까지 막히니, 역할을 나누고 규칙은 한 곳에서 관리한다.
4. **컨텍스트 주입 비용을 예산처럼 관리한다** — 검색 문서는 매 요청 입력 토큰을 늘리므로 Top-K·청크 크기로 비용·지연을 통제한다.
5. **인덱스는 갱신·중복·권한을 전제로 운영한다** — 원본이 바뀌면 버전·해시로 감지해 재색인하고(임베딩 모델 교체 시 전체 재색인), 필터·권한용 키는 metadata에 미리 넣어둔다.
6. **검색과 생성을 따로, 재현 가능하게 평가한다** — "검색 실패"와 "근거 무시"를 분리 측정하고, 평가셋을 코드화해 재실행 가능하게 둔다.

>**코퍼스(corpus)** : 검색 대상이 되는 문서 전체 모음. RAG에선 벡터 저장소에 적재해 둔 지식 문서 집합을 가리킨다. "정답이 코퍼스에 있다" = 검색하면 근거를 찾을 수 있다, "코퍼스 밖" = 관련 문서가 없는 도메인 밖 질문.

---

### 의문점

- **실무에서 RAG 파라미터는 어떤 기준으로 잡나?** `threshold`·`chunkSize`는 물론 `Top-K`·청크 `overlap`·임베딩 모델·생성 가드의 Fallback 발동 경계까지 — 처음에 무엇을 보고 
  정하고, 어떤 신호를 보고 조정하는지가 궁금하다.  
- **중복 적재는 실무에서 어떻게 갱신하는지 궁금하다.** 본문 해시 비교 전략 or 버전 관리 전략 or 그외?

---

### Round 5(Guardrail)에 시도할 수 있는 것

Round 5에서는 입력과 출력 양쪽에 별도의 가드레일 단계를 두는 것을 시도하고 싶다. 도메인 밖 질문이나 악성 입력은 검색하기 전에 걸러내고, 모델이 만든 답은 사용자에게 내보내기 전에 주어진 근거에 충실한지 검사하여, 검색 가드와 생성 가드만으로는 막지 못하던 환각과 범위 이탈을 한 단계 더 차단하는 것이다.

---

## 5주차 : Guardrail

### 1단계
- [01_guardrail_5scenario_report.md](docs/week5/stage1/01_guardrail_5scenario_report.md) — `InputGuardrailAdvisor`(order=5) 공격/정상 5종 시나리오 검증. 동작 축(차단 로그) + 비용 축(LLM 호출 0 = cost-0) 2축 평가
- [02_guardrail_design_decision.md](docs/week5/stage1/02_guardrail_design_decision.md) — `MAX_INPUT_CHARS`·정규식 vs 분류 LLM·`order=5` 배치·short-circuit cost-0(DoS) 4문항 설계 결정

### 2단계
- [03_masking_5scenario_report.md](docs/week5/stage2/03_masking_5scenario_report.md) — `OutputGuardrailAdvisor`(order=50) 마스킹/유출 5종 시나리오 검증. 동작 축(치환 로그) + 내용 축(민감정보 비노출) 2축 평가, 과잉/미흡 마스킹 실패 관찰 포함
- [04_guardrail_layering_design.md](docs/week5/stage2/04_guardrail_layering_design.md) — Output 가드 `order=50` 배치(바깥일 때 로그 PII 
  누출)·마스킹 제거 vs 대체·Input/Output 상호 불충분(실패 예시 각 1) 3문항 설계 결정

### 3단계
3가지 트리거(명시적 요청·법적/민원·감정 고조)를 우선순위(`EXPLICIT→LEGAL→ANGER`)로 판별해 **LLM 호출 전에** 상담원 전환 응답(연결 번호 `1600-0987`)을 반환한다. 규칙 매칭이라 전환은 수 ms로 끝나 LLM 왕복(수 초)을 건너뛴다.

- [05_handoff_report.md](docs/week5/stage3/05_handoff_report.md) — `HandoffDetector` 상담원 전환 7케이스 실험 및 설계 근거 
  - 우선순위 `EXPLICIT→LEGAL→ANGER`
  - LLM 호출 전 선검사 vs Advisor
  - LLM vs 규칙 기반 판별

### 4단계
LLM/Tool/RAG 호출이 실패해도 **스택 트레이스·예외 원문을 노출하지 않고** 일관된 안전 안내(연결 번호 `1600-0987`)를 돌려준다. 실패를 성격별 **4계층**(① 업무 실패 → ② 시스템 오류(transient/permanent) → ③ 못 잡은 예외 backstop → ④ 인프라 다운 컨트롤러 fallback)으로 나눠 처리한다.

- [06_tool_failure_handling_overview.md](docs/week5/stage4/06_tool_failure_handling_overview.md) — **Tool/LLM 실패 처리 4계층 통합 개요**

### 실무에서 개발한다면 고려할 점

이번 주차(가드레일·상담원 전환·실패 처리)를 **실제 서비스에 얹는 작업**으로 본다면, 무엇을 미리 결정·설계해야 하는지의 관점으로 정리했다.

#### 1. 가드는 "비용 위치"로 배치한다
- **Input 가드는 체인 최외곽**(낮은 order)에 둬 공격을 LLM 호출 **전에** short-circuit → 토큰 비용 0(DoS 1차 방어). **Output 가드는 Performance 로깅 안쪽**에 둬 마스킹된 응답이 로그에 찍히게(PII 로그 누출 방지).
- **정규식 가드는 표면 문자열만 본다** → 띄어쓰기·오타·우회 변형에 취약. 저비용 "빠른 경로"로 쓰고, 보강이 필요하면 분류 LLM을 **규칙 통과분에만** 적용(전건 LLM 분류는 cost-0 이점을 잃는다).

#### 2. 상담원 전환은 LLM 호출 전에 규칙으로 선검사한다
- 사람이 받아야 할 신호(명시적 요청·법적/민원·감정 고조)는 LLM 추론이 무의미 → **규칙 매칭(수 ms)으로 비용 0**. 겹치는 신호의 **우선순위**(예: 법적 > 감정)를 먼저 정의한다.
- 한계: 규칙은 자모/공백 분리·완곡 표현을 못 잡고(FN), 일반 명사를 오탐(FP)한다. 보강은 **2단계**(규칙 빠른 경로 → 미탐지 시 경량 분류 LLM)로.

#### 3. 실패는 성격별 계층으로 나눠 처리한다 — "raw 미노출"이 핵심
- 예외 원문(`e.getMessage()`·스택)을 LLM이나 고객에게 그대로 넘기면 **내부 정보가 유출되고 응답도 매번 달라진다.** 그래서 실패를 성격별로 나눠 처리한다 — **업무 실패는 구조화된 값으로 표현하고, 시스템 오류는 재시도 가능 여부(transient/permanent)로 분류하며, 도구가 미처 못 잡은 예외는 고정 문구 backstop이 받고, 인프라 다운은 컨트롤러 fallback이 처리한다.**
- **재시도 가능/불가를 구분**해야 정직하다. 일시 오류엔 "잠시 후 재시도", 영구 결함(버그)엔 "상담사 연결"을 안내 — 버그를 "재시도하세요"로 둔갑시키지 않는다.

#### 4. 결정적인 것과 비결정적인 것을 갈라서 검증한다
- 분류·로그 레벨·차단 여부는 코드가 정하므로 **결정적** → 단위 테스트로 값을 고정 검증한다.
- 고객에게 나가는 **최종 문구는 LLM이 생성하므로 비결정** → 글자 단언이 아니라 **방향(테마)** 으로 판정한다. 두 축을 한 테스트에 섞지 않는다.

### 의문점

- ❓ **Tool 오류 응답은 LLM 재해석이 나은가, 고정 문구 강제가 나은가?** 지금은 도구가 `errorKind`/지침을 결과로 주면 LLM이 고객 문구를 **재작성**한다(대화 톤은 자연스럽지만 비결정 — 이론상 `1600-0987` 누락 가능). 결정적 보장이 필요하면 LLM을 우회해 고정 문구를 직접 반환해야 하는데, **버그(PERMANENT)만 강제하고 일시 오류는 LLM에 맡기는 하이브리드**가 적정선인지 확신이 없다.
- ❓ **읽기 툴의 `null` 반환이 그대로 괜찮은가, 구조값으로 통일해야 하는가?** `getOrderDetail`/`getDeliveryStatus`는 "없는 주문"과 "형식 오류"를 둘 다 `null`로 합쳐 LLM이 구분하지 못한다. `cancelOrder`처럼 `{found:false, reason}` 구조값으로 통일하면 일관되지만, **정말 더 나은지 / 현행 `null`이 충분한지** 판단 기준이 없다.
- **가드를 분류 LLM으로 보강할 때 비용·지연의 적정 경계는?** 규칙 통과분에만 LLM을 태운다 해도, 신뢰도 임계·휴먼 폴백을 어떻게 잡아야 cost-0 이점을 살리면서 오탐/미탐을 줄일 수 있는지 감이 없다.
- **상담사 연결 번호(`1600-0987`) 같은 안내 문구가 코드 여러 곳에 복사돼 있다.** 번호가 바뀌면 전부 찾아 고쳐야 하고 하나라도 놓치면 고객마다 다른 안내가 나가니, 한 곳에 모아 관리하고 싶다. 그런데 
  *코드가 직접 내보내는 문구*(Controller fallback등)와 *LLM에게 지시로 주는 문구**(`@Tool` description·시스템 프롬프트)는 적는 위치가 달라서, 이 둘을 한 출처로 묶어 어긋나지 않게 
  유지하는 방법이 고민이다.
- **LLM이 tool 결과의 지침을 항상 따른다는 보장이 없다(비결정).** 가드레일을 description·프롬프트가 아니라 **코드로 강제**할 수 있는 범위는 어디까지이고, 어디부터는 LLM 신뢰에 맡길 수밖에 없는지 경계가 궁금하다.

---
