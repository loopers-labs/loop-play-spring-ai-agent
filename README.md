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

