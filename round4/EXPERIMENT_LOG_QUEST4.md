# Round 4 — QUEST 4단계: Observability + AI 코드 리뷰

## 측정 목적

QUEST 본문: *"PerformanceLoggingAdvisor로 RAG 추가 전후 토큰·시간 비용을 직접 관찰하고, AI가 만든 RAG 코드의 프로덕션 결함을 본인 라운드 발견과 매핑한다."*

핵심 의도 — *조용한 결함은 raw payload 캡처가 탐지의 유일 수단*(3단계 발견)을 *정량 비용*으로 확장. PerformanceLoggingAdvisor 토큰 로그 + RestClient body raw → *RAG 비용의 정체 = 컨텍스트 인플레이션*을 물리적으로 가시화.

## 측정 설계 (Observability — 9 ask)

### 3 phase 비교

| Phase | Advisor 구성 |
|---|---|
| **(a)** | `performanceAdvisor` 만 |
| **(b)** | `memoryAdvisor` + `performanceAdvisor` |
| **(c)** | `memoryAdvisor` + `ragAdvisor` + `performanceAdvisor` (현재 baseline) |

단일 질문 *"배달 완료 후에도 환불 받을 수 있나요?"* × 3 trial × 3 phase = **9 ask**.
각 trial 새 `X-Session-Id` (변수 격리 + Memory 누적 효과 제외).

### 자동화 인프라

- `run-token-cost-sweep.sh` — `AssistantController.defaultAdvisors(...)` sed 패치 + bootRun 재기동 + 측정 × 3 phase
- `measure-quest4.sh` — 단일 질문 N 반복 측정
- **RestClient DEBUG 임시 추가** (`org.springframework.web.client=DEBUG`) — Context 블록 raw payload 캡처용 (3단계 패턴 재활용)
- **Trap 자동 복원** — 어떤 상황에서도 (c) baseline + application.yml 복원

### raw 보존

- `.private/notes/round4/quest4-token-comparison.jsonl` 9 lines
- `.private/notes/round4/bootrun-quest4-phase-{a,b,c}.log` 총 ~106KB
- `bootrun-quest4-phase-c.log` 43KB — RestClient body raw payload 3건 포함

## 정량 결과

### Phase별 토큰·시간 (PerformanceLoggingAdvisor 로그)

| Phase | 입력 토큰 | 출력 토큰 (3 trial 평균) | 총 토큰 | ms (cold start 제외 평균) |
|---|:-:|:-:|:-:|:-:|
| **(a)** advisor만 | **1524** | 48.7 (45·46·55) | 1572.7 | 2903 |
| **(b)** +Memory | **1524** | 49.7 (42·55·52) | 1573.7 | 2606 |
| **(c)** +Memory+RAG | **2426** | 102.7 (105·103·100) | 2528.7 | 5394 |

### 핵심 비용 정체 — RAG 도입 효과 정량화

| 지표 | (a) → (c) 변화 | 백분율 |
|---|---|---|
| **입력 토큰** | 1524 → **2426** | **+902 (+59.2%)** |
| **출력 토큰** | 48.7 → 102.7 | +54 (+110.9%, ~2배) |
| **응답 시간** (cold 제외) | 2903ms → 5394ms | +2491ms (+85.8%, ~2배) |
| **응답 길이** (body 자수) | 75자 → 160자 | +85 (~2배) |

### Phase별 응답 비교 (trial=2 동일 질문)

| Phase | 응답 본문 |
|---|---|
| **(a)** | *"환불은 배달이 완료된 이후에도 가능합니다. 하지만 정확한 절차와 조건은 주문 상세 정보를 확인해야 합니다. 주문번호를 알려주시겠어요?"* |
| **(b)** | *"환불은 주문이 배달 완료된 이후에도 가능합니다. 하지만 이미 음식을 받고 계신다면 환불 이유가 명확해야 합니다. 어떤 이유로 환불을 원하시는지 말씀해주시겠어요?"* |
| **(c)** | *"배달 완료 후에도 특정 사유에 따라 환불이 가능합니다. 주문한 메뉴가 누락되었거나 품질 문제가 있는 경우 등은 환불을 신청할 수 있습니다. 하지만 단순 맛 불만족이나 배달 지연은 환불 대상에서 제외될 수 있으니 확인해주세요. 어떤 사유로 환불을 요청하시려나요? 주문번호를 알려주시겠어요?"* |

- **(a)·(b)**: 일반 안내. 정책 인용 0건. 조건 모름
- **(c)**: *"메뉴 누락 / 품질 문제 / 단순 맛 불만족 / 배달 지연"* — Context 4건 직접 인용

## Context 블록 raw 발췌 (phase-c bootrun log, USER content)

> 컨텍스트 인플레이션의 물리적 증거 — *RAG가 USER 메시지 안에 정책 문서를 통째로 박아 넣는다.*

```
[USER role content, 총 1673자]

배달 완료 후에도 환불 받을 수 있나요?

Context information is below, surrounded by ---------------------

---------------------
# 배달 완료 후 환불 정책

배달이 완료된 상태에서도 아래 사유에 한해 환불을 요청할 수 있습니다.

## 배달 완료 후 환불 가능 사유
1. **메뉴 누락**: 주문한 메뉴 중 일부가 도착하지 않은 경우.
2. **오배송**: 주문한 메뉴가 아닌 다른 메뉴가 도착한 경우.
3. **품질 불량**: 음식에 이물질이 포함되었거나, 상한 음식이 도착한 경우.
4. **수량 오류**: 주문 수량보다 적게 도착한 경우.

## 접수 시한
- 배달 완료 후 **24시간 이내** 접수만 유효합니다.
- 24시간을 초과하면 단순 맛 불만족과 함께 환불 대상에서 제외됩니다.

... (중략 — refund-basic 청크 일부) ...

# 환불 기본 정책

배달에서 주문 환불은 **주문 상태**와 **사유**에 따라 다르게 처리됩니다.

## 환불 가능 케이스
- **조리 시작 전 취소**: 주문 상태가 CREATED 또는 ACCEPTED인 경우, 전액 즉시 취소/환불 가능
- **음식 누락 / 오배송**: 배달 완료 후에도 사진 등 증빙 조건으로 전액 또는 부분 환불
- **배달 지연 과도 (60분 이상)**: 배달비 환불 또는 쿠폰 보상

## 환불 불가
- **조리 시작 이후 단순 변심**
- **배달 완료 후 24시간 초과**

## 환불 소요 기간
- 카드 결제: **최대 7영업일**
- 배달페이 / 머니 충전금: 즉시 복원
---------------------

Given the context and provided history information and not prior knowledge,
reply to the user comment. If the answer is not in the context, inform
the user that you can't answer the question.
```

→ 정책 청크 2건(*배달 완료 후 환불 정책* + *환불 기본 정책*)이 *USER 메시지 안*에 통째 박힘. 입력 토큰 +902의 정체.

## QUEST 본문 — 핵심 발견 5가지

### 1. **RAG = 입력 +59.2% / 출력 ~2배 / 응답 ~2배** (P5 정량화)

본 4단계의 가장 핵심 진단. 컨텍스트 인플레이션은 *추상이 아니라 +902 token / +2491ms*의 정량 비용.

### 2. **Memory 추가 비용 ≈ 0** (cold start, 새 sid 기준)

(a) → (b) 입력 토큰 1524 → 1524 (변화 없음). trial별 새 sid라 *Memory가 빈 상태*. → **Memory advisor 자체는 cold cost 0**. *누적 비용*은 본 실험에선 못 잡음(같은 sid로 다회 ask 필요).

### 3. **컨텍스트 인플레이션은 USER 슬롯에 들어감**

RestClient body raw 캡처로 직접 확인 — `QuestionAnswerAdvisor`는 정책 문서를 *USER 메시지 끝에* prepend(`Context information is below ... ---`). SYSTEM 슬롯은 BaedalPrompt가 차지. → **prefix cache 보존**이 자연스럽게 일어남 (시스템 프롬프트 불변).

### 4. **출력 길이도 RAG 따라 ~2배** (~49 → ~103 token, 75자 → 160자)

(a)·(b) 응답은 *일반 안내* — 정책 인용 0건. (c) 응답은 *4가지 환불 사유 + 24시간 시한 + 지연 정책*까지 인용. → Context를 받은 LLM은 *근거를 더 길게 답함* — 출력 비용까지 함께 증가.

### 5. **(a) vs (b) ms 차이 ~10%는 noise** (2903ms → 2606ms)

Memory advisor BeforeCall은 conversation 빈 상태에선 *조회 후 빈 결과 return*만. JVM warm-up·OS scheduler 영향이 더 큼. → Memory 자체의 ms 비용은 *cold start 기준 ~0*.

## QUEST 설계 결정 답

### Q1. RAG 도입으로 토큰 비용이 얼마나 늘었나, 어디로 박혔나?

- **입력 +902 token (+59.2%)**, 출력 +54 token (~2배), 응답 시간 +2491ms (~2배)
- 박힌 위치: **USER 슬롯** — `Context information is below ... ---` 블록으로 정책 청크 2건 통째 (chunk-800 × 2 ≈ 1600자, 한국어 토큰 비율 ~0.5)
- 정성 효과: 응답이 *일반 안내 → 정책 4건 직접 인용*으로 질적 변화

### Q2. (a)(b) 차이가 거의 없는 이유?

- trial별 새 sid → Memory가 *cold 상태*. 1턴이라 Memory advisor가 BeforeCall에서 *빈 결과 return*. 추가 토큰 0개
- 본 실험은 *cold start의 advisor 추가 비용*만 잡음. **Memory의 누적 비용은 별 측정 필요** (Round 3 *Memory 오염* 실험과 연결)

### Q3. RAG 도입의 본질을 한 줄로?

> **"RAG는 정책 청크 N건을 USER 메시지 안에 통째로 박아 넣는 비용을 지불하고, 그 대가로 출력에서 *근거 있는 인용*을 얻는다."**

## AI 코드 리뷰 (Gemini 3.5 flash 답안 결함 분석)

> 정제본은 `.private/notes/round4/quest4-ai-code-review-draft.md` 참조.
> 본 섹션은 *Top 3 결함 + 5 패턴 위반 분포*의 요약.

### 분석 방법

5 렌즈 병렬 분석(Workflow) — `checklist-vs-negation` / `dataflow-graph` / `observability` / `production-safety` / `cost-inflation` → 18 raw findings → Top 3 합의.

### Round 4 발견 5 패턴 위반 분포

| 패턴 | Gemini 코드 위반? | 한 줄 |
|---|:-:|---|
| **P1** 체크리스트 < 금지 | ❌ | systemPrompt 2문장 모두 긍정 + 메타 행동 양식. 부정 imperative 0개 |
| **P2** 룰 ROI 3-분리 | ❌ | ∞ 티어(도메인 가드) 완전 부재 |
| **P3** Advisor = dataflow 그래프 | ❌ | `builder.build()`만으로 슬롯 0개, vectorStore 직접 호출 |
| **P4** 조용한 결함 = raw payload | ❌ | PerformanceLoggingAdvisor 부재, wire body 캡처 부재 |
| **P5** RAG = 컨텍스트 인플레이션 | ❌ | `withTopK(3)` + threshold 미설정, Splitter 미적용 |

→ **5/5 위반**.

### Top 3 결함 요약

| Rank | 결함 | 매핑 |
|:-:|---|---|
| 🥇 1 | **Advisor dataflow 그래프 자체 미형성** — `builder.build()` + `vectorStore.similaritySearch` 직접 호출 + Memory 미연결 | P3 직격 |
| 🥈 2 | **도메인 가드 ∞ ROI + 결정적 fallback 부재** — systemPrompt 2문장에 ∞/8x/1x 티어 룰 모두 비어있거나 약함 | P1·P2 통합 |
| 🥉 3 | **PerformanceLoggingAdvisor 부재 + similarityThreshold 미설정** — 컨텍스트 인플레이션·재시드·환각 모두 운영 메트릭으로 비가시 | P4·P5 통합 |

### 한 줄 진단

> **Gemini는 RAG 컴포넌트를 *기능 단위*로 호출하지만 *Advisor 체인 = dataflow 그래프* 설계 자체를 포기했다.**
> 그 결과 P1~P5 다섯 패턴이 동시에 위반되고 모든 결함이 운영 메트릭으로 비가시화된다.

## 가설 검증 결과

| 가설 | 결과 |
|---|---|
| RAG 도입으로 입력 토큰이 의미 있게 증가 | ✅ **+902 token (+59.2%)** — 가설 부합 |
| Memory advisor의 cold cost ≈ 0 | ✅ (a) = (b) 1524 token — 가설 부합 |
| 컨텍스트는 USER 슬롯에 박힌다 | ✅ RestClient raw로 확인 — *시스템 프롬프트 prefix cache 보존* |
| 출력 토큰도 함께 증가 | ✅ ~2배 (49 → 103) — *근거 있는 인용으로 응답 길어짐* |
| Gemini AI 코드는 Round 4 발견 5 패턴 중 다수 위반 | ✅ **5/5 위반** — 가설 강하게 부합 |

## 자가 점검 (4단계)

- [x] PerformanceLoggingAdvisor 토큰 로그 9건 정량 raw 보존
- [x] 3 phase 비교 표 (입력·출력·총 토큰·ms·body 길이)
- [x] RestClient body raw로 USER 슬롯 Context 블록 직접 캡처
- [x] (a)/(b) 차이가 거의 없는 이유 분석 (cold start + 빈 Memory)
- [x] Gemini AI 코드 5 렌즈 병렬 분석 → Top 3 합의
- [x] Top 3 fix 제안이 본 프로젝트 실제 클래스·줄 번호 수준 상세
- [x] 잔여 결함 7건의 Top 3 흡수 여부 표로 명시
- [x] 측정 후 baseline 복원 자동 검증 (AssistantController + application.yml + bootRun)

## 정식 결정 — *5 패턴 모두 baseline 유지*

본 4단계는 *측정·검증*이지 *변경*이 아니다. 4단계 산물은:

1. **정량 raw 데이터** — RAG 비용 정체 = +59.2% 입력 토큰
2. **AI 코드 리뷰 자산** — Round 4 발견 5 패턴이 *실전 검증 도구*로 작동함을 확인
3. **3축 관측 인프라 검증** — PerformanceLoggingAdvisor + RestClient DEBUG + raw jsonl 보존 조합이 *프로덕션 결함 진단의 표준 패턴*

## 산출물 (4단계)

- `round4/EXPERIMENT_LOG_QUEST4.md` (본 파일) — Observability 정량 + AI 코드 리뷰 통합 정제본
- `.private/notes/round4/quest4-ai-code-review-draft.md` — Gemini 코드 결함 분석 raw + Top 3 + 잔여 7건
- `.private/notes/round4/decisions-log-quest4.md` — 판단 기록 raw
- `quest4-token-comparison.jsonl` 9 lines
- `bootrun-quest4-phase-{a,b,c}.log` ~106KB (RestClient body raw 포함)
- `measure-quest4.sh` + `run-token-cost-sweep.sh`

## 미해결·이월 (Round 5)

- **Memory 누적 비용** — 본 실험은 cold만, *같은 sid로 10턴+* 누적 시 입력 토큰 추이는 별 측정 필요 (3단계 Memory 오염과 연결)
- **임베딩 호출 비용** — `PerformanceLoggingAdvisor`는 *LLM 호출만* 캡처. 임베딩 호출 토큰·시간은 별 advisor 필요
- **응답 품질 sentinel** — 토큰·ms만 잡는 현 PerformanceLoggingAdvisor에 *"context 인용 여부"* boolean·*"fallback 발동 여부"* boolean 추가하면 *조용한 회귀*까지 잡힘
- **Gemini fix 적용 후 회귀 측정** — Top 3 fix를 Gemini 코드에 적용한 후 동일 sweep으로 *비용·품질 개선 정량화*는 Round 5 의제
- **재시드 idempotency 측정** — `KnowledgeLoader.alreadyLoaded` 가드의 효과 정량 측정 (배포 1회당 절감 토큰)
