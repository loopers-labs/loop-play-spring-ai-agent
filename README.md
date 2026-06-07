# loop-play-spring-ai-agent

Spring AI 기반 배달 고객 상담 에이전트 학습 프로젝트.
Spring Boot 3.4.1 + Spring AI 1.0.0 + Ollama `qwen2.5` (`temperature: 0.3`).

```bash
./gradlew bootRun
curl -X POST localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤이에요?"}'
```

## 진행 현황

| Round | 단계 | 상태 | 핵심 결과물 |
|---|---|---|---|
| **Round 1** | 1단계 · 기본 API + System Prompt + Structured Output | ✅ | `/api/v1/support`, 12필드 record, 7섹션 prompt |
| Round 1 | 2단계 · Prompt Lab + 실패 관찰 | ✅ | `/api/v1/prompt-lab` 다축 메트릭 + 시나리오 4종 + [금지] ablation 보고서 |
| Round 1 | 3단계 · Streaming | ✅ | `/api/v1/chat/stream` (SSE) + Structured Output 충돌 발견 |
| Round 1 | 4단계 · Observability + AI 코드 리뷰 | 🟡 | `PerformanceLoggingAdvisor` + 토큰·시간 측정 + advisor 누적 bug 발견·수정 + `@ControllerAdvice` 글로벌 에러 핸들러 (AI 코드 리뷰는 Round 2 4단계에서 진행) |
| Round 1 | 공통 · 학습 기록 | ✅ | 1단계 의문 + 2~4단계 잠정 답 + Round 2 연결 |
| **Round 2** | 1단계 · Tool 3개 + Mock 4건 + 시나리오 5종 | ✅ | `OrderTools` (`getOrderDetail`/`getDeliveryStatus`/`cancelOrder`) + `AssistantController` + 단일 prompt 통합 |
| Round 2 | 2단계 · 멱등성 관찰 + 분기 제거 실험 | ✅ | admin API + Outcome 4 경로 + 분기 제거 실험 (`canceledReason` 덮어쓰임 재현) |
| Round 2 | 3단계 · Tool description A/B/C 정량 비교 | ✅ | `getDeliveryStatus` 3 variants × 5회 (A 5/5, B 3/5, C 4/5) + description 두 역할 발견 |
| Round 2 | 4단계 · Observability + AI 코드 리뷰 | ✅ | Tool 왕복 4단계 + 토큰 2배 측정 + GPT-5.5 코드 결함 3가지 분석 |
| Round 2 | 공통 · 학습 기록 | ✅ | 배운 것 11가지 + 의문점 3가지 + Round 3 (Memory) 아이디어 3가지 |
| **Round 3** | 1단계 · Memory 3레이어 + 세션 분리 | ✅ | `ChatMemoryConfig` 3빈 + `SessionController` + `X-Session-Id` 세션 분리 + 시나리오 5종 (세션 격리↔누출) |
| Round 3 | 2단계 · `MAX_MESSAGES` 정량 비교 + 회귀 ablation | ✅ | 2/20/MAX_VALUE 토큰·지시대명사 + `[대화 맥락 규칙]` 제거(Tool 20→80%) + temperature 검증("모델 탓" 반박) |
| Round 3 | 3단계 · InMemory vs JDBC 영속화 | ✅ | JDBC 전환 함정 5종 + 재시작 실험 (JDBC 4건↔InMemory 0건) + 의사결정 트리 |
| Round 3 | 4단계 · Observability + AI 코드 리뷰 | ✅ | 10턴 토큰 3559→4093 + Memory 포함 2회차 프롬프트 전문 + codex 결함 3종 |
| Round 3 | 공통 · 학습 기록 | ✅ | 배운 것 3 + 의문점 + Round 4 (RAG) 아이디어 |

---

# Round 1 — Spring AI 배달 상담 에이전트

## 공통 — 학습 기록

### 1단계 진행 중 느낀 점

**(1) LLM 응답의 정합성을 어떻게 검증할 것인가**
내가 정의한 프롬프트를 LLM이 어느 정도 정합성에 맞춰 답변해줄 수 있을지 전혀 감이 오지 않았다.
기존 일반 코드는 **테스트 코드로 정합성을 지켜가며** 진행할 수 있었지만, LLM이 주는 답을
어떤 식으로 테스트해야 할지는 2단계에서 본격적으로 고민해봐야 할 것 같다.
(같은 입력에도 응답이 매번 달라지는 비결정성이 단순 `assertEquals`로는 잡히지 않는다는 점.)

**(2) AI 시스템을 어떻게 신뢰할 것인가**
아무리 프롬프트를 잘 작성해서 테스트해도 LLM 답변이 **100% 내 의도대로 흘러가게 할 수 있을 것 같지 않다**.
그런 시스템을 내가 어떻게 신뢰할 수 있을까?
물론 프로그래밍에 100%라는 수치는 존재하지 않지만, 그렇다면 나는 어떤 방향으로
**100%에 가까운 버그 없는 AI agent 시스템**을 만들 수 있을까가 핵심 고민으로 남았다.

**(3) 프롬프트 튜닝의 리소스 부담**
프롬프트란 정해진 게 없고 내가 만들어가는 것인데, *어떤 프롬프트를 작성해야 결과가 더 잘 나오는지* 를 2단계에서 검증해야 할 것 같다.
그런데 이게 **시나리오 × 프롬프트 변형 × 반복 호출** 의 조합이라 적지 않은 리소스가 들 것 같다.
더 좋은 방법(자동 평가 지표·골든셋·LLM-as-Judge 등)이 있을지 찾아볼 필요가 있다.

---

### 2~4단계 진행하며 1단계 의문에 얻은 답 (잠정)

> 본인 언어로 다듬을 영역. 각 단계의 보고서에서 발견한 근거를 기반으로 정리.

**(1) "LLM 응답의 정합성 검증" → *분포 + 계약 검증***

2단계 PromptLab 직접 측정으로 확인:
- 분류 단위(`category`/`intent` enum)는 Structured Output schema 가 anchoring 하여 같은 입력 5~10회 호출에 모두 동일 → `assertEquals` 가능
- 자유 텍스트(`customerMessage`/`summary`/`nextAction`)는 비결정적 → 분포 검증 (`customerMessageEchoRate`, `prohibitionViolationRate`, `koreanResponseRate` 같은 비율 메트릭)
- ⚠️ 메트릭 자체가 거짓말할 수 있음 (2단계 ablation 발견 — 의미적 위반 못 잡고, echo 오탐 발생) → **raw responses 인용·사람 검토** 가 진짜 가드레일

**(2) "신뢰할 수 있는 AI 시스템" → *다층 구조 + 사람 인계***

1단계 설계의 핵심 요소들이 이미 이 방향이었음을 회고:
- `recommendedRouting` 5단계 (`AUTO` → `AGENT_REVIEW` → `MANAGER_REVIEW` 등) — LLM 응답이 *얼마나 자동 처리해도 안전한가* 의 명시
- `CLAIM` 카테고리 위임 — 보상 결정은 사람에게
- `missingInfo` 명시 — 정보 부족 시 자동 처리 차단

3단계 `STREAMING_PROMPT` 분리도 같은 패턴 — *Structured ↔ Streaming 의 구조 분리 가 충돌 방지*. 4단계 `@ControllerAdvice` 글로벌 에러 핸들러도 *예외를 표준 형태로 처리* 하는 구조.

→ **AI 신뢰는 *모델 신뢰* 가 아니라 *구조 신뢰* 다.** 시스템이 잘못된 응답을 받아도 안전하게 처리할 수 있는 구조.

**(3) "프롬프트 튜닝 리소스" → *자동화된 PromptLab + LLM-as-Judge 가설***

2단계에 직접 구현한 `PromptLabController` 다축 메트릭이 한 가지 답:
- 시나리오 × 프롬프트 변형 × 반복 호출을 API 한 번으로 자동화 → 비용 ↓
- `categoryConsistency` / `echoRate` 등 분포 메트릭으로 정량 비교 가능

다만 메트릭 자체의 한계(2단계 ablation 발견: 의미적 위반 못 잡음 + echo 오탐) → **LLM-as-Judge** (응답을 별도 LLM 으로 평가) 가 다음 라운드 후속 과제.

또 4단계 advisor 측정에서 발견 — **운영 비용의 95%가 system prompt 토큰** → 프롬프트 튜닝은 *길이 관리* 도 함께 고민해야 함. 단순히 *"좋은 응답을 만드는 프롬프트"* 가 아니라 *"좋은 응답을 만드는 가장 짧은 프롬프트"* 가 운영 관점의 목표.

---

### 본 라운드의 가장 큰 발견 — 한 줄로

> **AI 시스템의 신뢰성은 *모델 응답* 이 아니라 *시스템 구조* 에서 온다.**
> `summary`/`customerMessage` 청자 분리, `CORE_GUARDRAILS` 공유, `STREAMING_PROMPT` 분리, `*_REVIEW` 라우팅, `@ControllerAdvice` — 이 모든 결정의 공통점은 *"LLM 이 어떻게 답하든 시스템이 안전한 흐름을 보장한다"*.

---

### Round 2 (Tool Calling) 연결 아이디어

본 1단계 설계 자산이 Round 2 에서 어떻게 활용될지:

| 1단계 자산 | Round 2 활용 |
|---|---|
| `Intent` 26개 enum | `@Tool` 메서드 1:1 매핑 키 (`DELIVERY_LOCATION` → 배달 추적 API, `ORDER_CANCEL` → 주문 취소 API) |
| `missingInfo.isEmpty()` | Tool 호출 가능 신호. 비어 있지 않으면 재질의 (UI 또는 chained call) |
| `recommendedRouting=DELIVERY_PARTNER`/`STORE_CONFIRMATION` | 외부 시스템 Tool 호출 분류 |
| `SupportService` 의 `ChatClient` 캐싱 | Tool 등록 누적 bug 사전 회피 (4단계 발견의 `ChatClient.builder(chatModel)` 패턴 재사용) |
| `PerformanceLoggingAdvisor` | Tool 호출 비용도 함께 측정 |
| `CORE_GUARDRAILS` | Tool 호출 응답에도 동일한 [금지] 가드레일 적용 |

본 라운드의 발견 *"AI 신뢰는 구조 신뢰"* 가 Round 2 에서 검증될 자리:
- Tool 호출 결과 검증 (LLM 이 잘못된 Tool 호출하면?)
- Tool 결과의 자동 처리 vs 사람 검토 분기 (`recommendedRouting` 패턴 재사용)
- 외부 시스템 실패 시 fallback (4단계 `@ControllerAdvice` 의 `TransientAiException` 패턴을 외부 API 호출에도 확장)

---

## 1단계 — 기본 API + System Prompt + Structured Output

`BaedalPrompt.SYSTEM_PROMPT` 7섹션 (역할 / 규칙 / 금지[8] / 분류[11 Category] / 응답 작성 / 정보 수집 / 보상 처리) + `SupportResponse` 12필드 record + 5 enum + `SupportController.triage()` (`POST /api/v1/support`, `.entity(SupportResponse.class)`).

### 시나리오 3종 (`POST /api/v1/support`)

| # | 입력 요지 | category | routing |
|---|---|---|---|
| 1 | 배달 위치 (주문번호 포함) | `DELIVERY` | `AUTO` |
| 2 | 주문 취소 + 환불 | `ORDER` | `AUTO` |
| 3 | 라이더가 음식 엎음 | **`CLAIM`** | `AGENT_REVIEW` |

→ 시나리오 3이 `CLAIM` + `AGENT_REVIEW` + `nextAction="보상 가능 여부 검토"` 로 **보상 위임 설계대로 작동**.

### 핵심 발견

1. **`Category` 5→11 확장** — "라이더가 음식 엎음"을 DELIVERY/REFUND 로는 라우팅이 모호 → `CLAIM` 신설로 명확한 처리 흐름
2. **`summary`/`customerMessage` 청자 분리** — 내부 기록(3인칭) vs 고객 노출(존댓말)
3. **`suggestedCompensationType` 의도적 제외** — LLM 보상 추천은 `[금지]` 와 충돌 → `CLAIM` 분류 + `*_REVIEW` 라우팅으로 위임
4. **`missingInfo`/`confidenceLevel` 비결정성** — `temperature: 0.3` 호출별 변동 (2단계 정량 측정 대상)

### 상세 보고서

- [1단계 설계 보고서](reports/week1/stage1/support-api-design-report.md) — 시나리오 3종 전체 JSON + 설계 결정 4가지(금지 8 / Category 11 / 추가 필드 / 7섹션 분리) + 관찰 노트

## 2단계 — Prompt Lab + 실패 관찰

`POST /api/v1/prompt-lab` — 다축 메트릭(분류 일관성·언어·echo·금지 위반·정보 정확도) 정량 측정 + 공격 시나리오 ablation.

### 단순 vs 구조화 프롬프트 비교 (시나리오 4종 × 5회)

| 시나리오 | 단순 echo | 구조화 echo | 단순 korean | 구조화 korean | 분류 동일? |
|---|---:|---:|---:|---:|---|
| S1 배달 위치 | 1.0 | 1.0 | 0.0 | 1.0 | ✓ DELIVERY |
| S2 취소·환불 | 1.0 | 0.8 | 0.0 | 1.0 | ✓ ORDER |
| S3 라이더 사고 | 1.0 | **0.0** | 0.8 | 1.0 | ✓ CLAIM |
| S4 음식 짠 불만 | 1.0 | **0.0** | 0.0 | 1.0 | **❌ ORDER vs CLAIM** |

→ **분류 차이가 보이는 자리: S4 (모호 케이스)**. echo 차이는 *예시(few-shot) 매칭* 에서 결정 (S3 = 예시 2, S4 = 의미적 유사).

### [금지] 제거 ablation (공격 시나리오 3종 × 5회)

| 시나리오 | [금지] 있음 prohibition | [금지] 없음 prohibition | 실제 위반 패턴 |
|---|---:|---:|---|
| ATK1 사장님 전화번호 | 0.0 | 0.0 | `nextAction: "연락처 제공"` 의미적 위반 (메트릭 거짓 안심) |
| ATK2 환불 협박 | 0.0 | 0.0 | `nextAction: "환불 처리 진행"` 60~80% (메트릭 거짓 안심) |
| ATK3 쿠팡이츠 비교 | 0.4 | **1.0** | 4/5 echo 로 인한 키워드 오탐 (실제 자발 위반 1/5) |

→ 메트릭이 **의미적 위반(0.0 거짓 안심) 못 잡고 echo 오탐(1.0 거짓 경보)** 을 일으킴.

### 핵심 발견 (보고서 종합)

1. **분류 단위는 Structured Output schema 가 anchoring** — 단순/구조화 차이 거의 0 (S4 모호 케이스만 갈림)
2. **진짜 차이는 자유 텍스트(echo·언어·nextAction 어조)** — 다축 메트릭으로 정량화 가능
3. **`customerMessageEchoRate` 는 예시(few-shot) 매칭에 의해 결정** — instruction 약하고 예시 강함
4. **`prohibitionViolationRate` 의 두 한계** — 의미적 위반 못 잡음(거짓 안심) + 입력 echo 오탐(거짓 경보)
5. **진짜 사고는 `nextAction` 의미 위반 + `routing=AUTO`** — 메트릭이 잡지 못하는 자리. `*_REVIEW` 라우팅이 마지막 방어선

### 상세 보고서

- [단순 vs 구조화 비교](reports/week1/stage2/structured-prompt-comparison-report.md) — 시나리오 4종 × 다축 메트릭, 메트릭 한계 분석
- [[금지] 제거 ablation](reports/week1/stage2/prohibition-ablation-report.md) — 공격 3종 + 사고 시나리오 정성 분석

## 3단계 — Streaming (SSE)

`POST /api/v1/chat/stream` 구현. 1차 구현에서 *Structured Output ↔ Streaming 충돌* (raw JSON 청크 노출) 발견 → **`STREAMING_PROMPT` 분리** (`CORE_GUARDRAILS` 공유) 로 수정 → 자연어만 흐르는 정상 동작.

### 측정 (warm, 시나리오 3, 각 5회 평균)

| | 평균 | min~max |
|---|---:|---:|
| 동기 | 1.38s | 1.05~2.25 |
| 스트리밍 | 1.11s | 0.96~1.31 |

→ **실험 1의 9초/1초 차이는 cold start 였다.** warm + 짧은 응답에선 streaming 의 시간 이득 거의 없음.

### 핵심 발견

1. **`Structured Output` 과 `Streaming` 은 한 system prompt 로 못 씀** — 용도별 분리 필수
2. **`CORE_GUARDRAILS` 공유** — `[역할]`/`[규칙]`/`[금지]` 가드레일은 두 prompt 동시 적용 (DRY)
3. **체감 속도는 cold/warm + 응답 길이 의존** — cold 시 동기 9초, warm + 짧은 응답엔 거의 동일
4. **한 번의 측정은 위험** — N회 평균 + 변동폭 함께 봐야 (이상치 2.25s)
5. **Streaming 은 UX 축** — 신뢰성은 `*_REVIEW` 라우팅·구조에서 옴 (2단계 결론 유지)
6. **SSE 메타데이터 확장** — `event: token`(자연어) + `event: meta`(12필드 JSON) 분리. LLM 2회 호출(비용 ×2) trade-off

### 적용 범위

| 케이스 | 적용 |
|---|---|
| 자유 텍스트 (`/api/v1/chat/stream`) | ✅ `STREAMING_PROMPT` |
| Structured JSON (`/api/v1/support`) | ❌ 동기 유지 |

### 상세 보고서

- [Streaming 실험](reports/week1/stage3/streaming-report.md) — 1·2차 구현 비교 + `STREAMING_PROMPT` 분리 근거 + 모델별 체감 속도 + 프론트엔드 영향(`EventSource`/`fetch+ReadableStream`) + **SSE 메타데이터 확장 옵션 A/B/C**

## 4단계 — Observability + AI 코드 리뷰

`PerformanceLoggingAdvisor` (`CallAdvisor`) — 응답 시간·토큰을 `log.info`. `SupportService` 양쪽 `ChatClient` 등록.

### 토큰 측정

| | inputTokens | outputTokens | elapsedMs |
|---|---:|---:|---:|
| 6 케이스 평균 | **2651** | 154 | 5858 |

→ `inputTokens` 변동 14 토큰뿐 (메시지 길이 영향 미미) — **운영 비용의 95%가 system prompt**.

System Prompt 2배 실험: 글자 7,144→14,290 인데 `inputTokens` 2,655→**4,096 (+54%)**. **글자 2배 ≠ 토큰 2배** (BPE 압축). 길이 관리가 비용 최적화 핵심.

### 핵심 발견

1. **운영 비용 95%가 system prompt** (`inputTokens/totalTokens = 94.5%`)
2. **글자 2배 → 토큰 +54%** — BPE 패턴 압축
3. **⚡ `PromptLabController` advisor 누적 bug 발견** — `ChatClient.Builder` singleton 에 매 요청 `defaultAdvisors` 누적 → 2회 실행. `ChatClient.builder(chatModel)` 정적 팩토리로 수정. **Observability 가 비용 측정을 넘어 *결함 발견* 도구임을 직접 검증** (1단계 셀프리뷰가 가리킨 "매 요청 build 누적" 자리).

### 미진행

- **AI 코드 리뷰** — Round 2 4단계에서 GPT-5.5 코드 결함 3가지 분석으로 진행.

### 상세 보고서

- [Observability 측정](reports/week1/stage4/observability-report.md) — 6 케이스 토큰 표 + 2배 실험 + advisor 누적 bug 원인·수정 + Observability 의 진짜 가치(*결함 발견 도구*)

---

# Round 2 — Tool Calling

## 1단계 — Tool 3개 + Mock 4건 + 시나리오 5종

`OrderTools` 3개 (`getOrderDetail` / `getDeliveryStatus` / `cancelOrder`) + `OrderMockService` 6건 (1234~1239) + `AssistantController` (`/api/v1/assistant`) + `SupportController` 양쪽 Tool 등록.

### 시나리오 5종 응답 (`POST /api/v1/assistant`, 5차 시도 — prompt 통합 후)

| # | 입력 | 기대 Tool | 실제 Tool 호출 | 응답 요약 |
|---|---|---|:---:|---|
| S1 | "주문번호 2024-1234 배달 어디쯤이에요?" | `getDeliveryStatus` | ✅ | *"주문 2024-1234는 현재 배달 중이며 라이der가 역삼역 사거리 부근에 있습니다. 약 15분 내 도착 예정이에요."* (⚠️ "라이더" → "라이der" 한·영 혼용) |
| S2 | "주문번호 2024-1234 어떤 메뉴 주문했어요?" | `getOrderDetail` | ✅ | *"주문번호 2024-1234의 주문 내역은 다음과 같습니다: 허니콤보 1개 (23,000원), 콜라 1.25L 1병 (3,000원). 총 26,000원입니다."* |
| S3 | "주문번호 2024-1235 방금 시킨 건데 취소해주세요" | `cancelOrder` | ❌ | *"주문 2024-1235를 취소해드리겠습니다."* + 응답 본문에 `{"name": "cancelOrder", "arguments": ...}` JSON 누출 (qwen2.5 한계) |
| S4 | "주문번호 2024-1236 취소해주세요" | `cancelOrder` (NOT_CANCELABLE) | ✅ | *"주문 2024-1236는 이미 배달 완료된 상태라서 취소할 수 없습니다. 매장과 직접 확인해 보시는 것이 좋을 것 같습니다."* (+ 중국어 한 줄 섞임) |
| S5 | "주문번호 2099-9999 배달 어디예요?" | `getDeliveryStatus` (null) | ✅ | *"주문번호 2099-9999를 찾을 수 없습니다. 다시 한 번 확인하신 후 요청해주세요."* |

→ **Tool 호출 4/5 (80%)**. S3 만 LLM 이 Tool 호출 형식을 *자연어 응답* 안에 그대로 적어버려 Spring AI 의 Tool 디스패치가 발동 안 됨 (qwen2.5 Tool Calling 안정성 한계).

각 시나리오의 5차까지 진화 raw 응답·`[Tool]` 콘솔 로그 전체는 [상세 보고서](reports/week2/stage1/tool-implementation-report.md) 의 *1차 시도 → 5차 시도* 섹션 참고.

### 핵심 발견

1. **Tool 호출은 *옵션* — LLM 이 호출 안 할 수도 있음.** 1단계 1차 시도 호출률 0/5
2. **prompt 분리(Round 1) → Tool 회피 부작용.** Round 1 의 *"확인 후 안내"* 회피 표현이 Tool 호출 방해로 작용
3. **단일 prompt 통합** (Round 2 결정) 으로 호출률 4/5 도달. 5차까지의 prompt 강화 진화 과정에서 *비단조 효과* 관찰 — 한 곳 손볼 때 다른 시나리오 깨짐
4. **`qwen2.5` Tool Calling 한계** — S3 의 *JSON Tool call 텍스트 누출* 패턴 발견

### 상세 보고서

- [Tool 구현 + 시나리오 5종](reports/week2/stage1/tool-implementation-report.md) — 1차~5차 진화 흐름 + 단일 prompt 통합 결정 근거 + 설계 결정 3가지 (OrderDetailView 의도적 제외 필드 / description 언어 결정 / OrderTools 분리 기준)

## 2단계 — 멱등성 관찰 + 분기 제거 실험

`AdminOrderController` 추가 (admin API — Tool 직접 호출). `qwen2.5` Tool 호출 불안정성 때문에 결정적 검증 채널 분리.

### 핵심 발견

1. **Outcome 별 Tool 호출률이 다름** — ALREADY_CANCELED 100% / NOT_FOUND 80% / CANCELED 50% / NOT_CANCELABLE 20%. *안전한 거부* 는 안정, *실제 상태 변경* 은 회피
2. **멱등성 분기 제거 실험으로 `canceledReason` 덮어쓰임 직접 재현** — 1239 두 번째 호출에서 "마음 바뀜" → "진짜 한 번 더 취소" 로 덮어씌워짐
3. **더 큰 위험 — DELIVERED 강제 취소** — 분기 제거 후 *이미 배달 완료된 주문도 취소 처리* 됨. 데이터 정합성 붕괴
4. **LLM 응답 *"환불 절차에 들어갈 예정"* 두 번 발언** — 이중 환불 트리거 위험

### 상세 보고서

- [멱등성 ablation 실험](reports/week2/stage2/idempotency-ablation-report.md) — Outcome 4 경로 결정적/자연 응답 raw 인용 + 실험 1·2 정확한 결과 + 5가지 고객 오해 + 5가지 프로덕션 장애 시나리오 + Outcome enum 설계 결정 3가지

## 3단계 — Tool description A/B/C 정량 비교

`getDeliveryStatus` 의 `@Tool(description)` 을 3가지 버전으로 바꿔가며 같은 질문 5회씩 호출.

### 측정 결과

| 버전 | description | Tool 호출 (5중) |
|---|---|---:|
| A (기준) | 한국어 상세 (~150 토큰) | **5/5** |
| B (빈약) | `"배달 정보 조회"` (한 줄) | 3/5 |
| C (오해 유발) | `"주문번호 조회용. 메뉴와 결제 금액만 반환한다."` (거짓) | 4/5 |

### 핵심 발견

1. **description 의 두 역할 분리** — Tool 호출 결정 vs 결과 해석. 거짓 description 이 호출률은 깎지만 *결과 해석은 망가뜨리지 않음*
2. **B 의 *"고객에게 데이터 되묻기"* 응답** — 빈약 description 으로 LLM 이 *"라이더 위치 알려주시면..."* 식 황당한 응답
3. **description 필수 4가지 본인 체감 중요도 순** — (1) 호출 시점 Trigger Words (2) 책임 영역 (3) 입력 형식 (4) 실패 반환

### 상세 보고서

- [description A/B/C 정량 비교](reports/week2/stage3/tool-description-ab-report.md) — 각 버전 5회 raw 응답 + "오래된 주석" 방지 대책 5가지 (테스트 / PR 체크리스트 / Contract Test / 운영 모니터링 / javadoc 동기화)

## 4단계 — Observability + AI 코드 리뷰

`PerformanceLoggingAdvisor` 확장 (`[LLM-REQ]` 요청 시점 로깅 추가) + Tool 왕복 4단계 캡처 + Round 1 vs Round 2 토큰 비교 + GPT-5.5 코드 결함 분석.

### 토큰 측정

| 케이스 | inputTokens |
|---|---:|
| Tool 호출 미발동 | 3,545 |
| Tool 호출 발동 | **7,246 (2.04배)** |

→ 2차 LLM 호출이 비용 폭증 주범 — 1차 컨텍스트 전체가 재전송됨. Tool 호출 N회 시 *누적적* 증가.

### GPT-5.5 코드 결함 3가지

| # | 결함 | 우리 라운드 정답 |
|---|---|---|
| 1 | 멱등성 분기 부재 — ALREADY_CANCELED 와 NOT_CANCELABLE 가 같은 message 로 묶임 | Round 2 2단계 — `Outcome.ALREADY_CANCELED` 분기 + 첫 reason 유지 |
| 2 | 예외 그대로 throw — LLM Fallback 불가 (`IllegalArgumentException` 등) | Round 1 1단계 — 예외 대신 `Outcome.NOT_FOUND` 결과 값 반환 |
| 3 | `boolean success` — Outcome 구분 없음, 후속 시스템 분기 못 함 | Round 2 1단계 — `Outcome` enum 4~5종 |

→ **GPT-5.5 코드의 절반은 우리 가이드와 일치** (description 한국어·도메인 분리·본인 소유 검증). *프로덕션 운영의 디테일* 에서 결함. 본인 배운점 K — *"AI 가 기본 구조는 잘 잡아도 도메인 디테일은 인간이 채워야"* 를 직접 검증.

### 상세 보고서

- [Observability + AI 코드 리뷰](reports/week2/stage4/observability-and-ai-review-report.md) — Tool 왕복 4단계 흐름 + 토큰 출처 분해 + GPT-5.5 결함 3가지 개선 코드 + Round 1·2 전체 학습 흐름 종합

---

## 공통 — 학습 기록

### 내가 배운 것

**1. Tool Calling 의 흐름 자체**

1주차에는 *"LLM 한테 요청 → 카테고리 받음 → 코드에서 분기 → 다시 prompt 만들어 호출"* 식으로 개발자가 흐름을 다 짜는 구조라고 생각했다. 그런데 Tool 은 LLM 이 동작하면서 *상황에 맞게 스스로* 호출하더라. Spring AI 가 그 흐름을 구조적으로 잘 만들어놨다는 인상이었다.

근데 또 의문 — *"Tool 목록 주면 LLM 이 반드시 그 중 하나는 호출할까?"* — 답은 아니였다. 호출 안 하기도 하고 멋대로 판단하기도 했다. 2주차 테스트의 대부분이 *"내가 원하는 Tool 을 LLM 이 호출하게 만드는 방법"* 을 찾는 과정이었다.

**2. prompt 는 사이드이펙트에 예민하다**

지금까지는 내가 수정한 코드가 어디에 영향을 미치는지 IDE 가 알려줬다. 그런데 prompt 는 한 줄 고치면 무수히 다양한 유저 질문에 영향이 가고, **어디까지 영향을 미칠지는 직접 돌려봐야만 안다**. 1단계 1~5차 진화 과정에서 호출률이 *계속 좋아진 게 아니라* 회귀도 발생했다.

**3. 멱등성의 수준 — 같은 행동을 두 번 해도 안전한가**

2단계 실험으로 멱등성을 처음 *체감* 했다. 같은 주문에 `cancelOrder` 를 두 번 호출했을 때 — *멱등성 분기를 통째로 제거하면* 두 번째 호출에서 첫 호출의 사유가 덮어씌워지고, *이미 배달 완료된 주문* 도 강제로 취소 상태로 변경된다. 더 무서운 건 LLM 응답이 두 번 모두 *"환불 절차에 들어갈 예정"* 이라 사용자 입장에서 *이중 환불* 로 오해할 수 있다는 점이다.

멱등성에는 *에러 / 무시 / 같은 응답 재전달* 세 가지 수준이 있고, 우리 `cancelOrder` 는 마지막이 맞았다 — 첫 호출의 사유 유지로 운영 추적 가능 + LLM 도 자연스럽게 *"이미 취소된 주문"* 으로 답할 수 있다. 결제처럼 *돈이 나가는 자리* 라면 *에러* 가 더 안전하다는 것도 같이 배웠다.

**4. prompt 는 상세해야 한다**

3단계 A/B/C 테스트에서 LLM 은 *잘못된 prompt (C)* 를 *정보 없는 prompt (B)* 보다 더 잘 Tool 호출했다. *잘못된 prompt 가 좋다* 는 뜻이 아니라, **상세하지 못한 prompt 가 잘못된 prompt 보다 더 위험하다** 는 케이스를 명백히 보여줬다. 빈약 description (B) 은 *"라이더가 어디 있는지 알려주시면..."* 같이 시스템이 고객에게 데이터를 되묻는 황당한 응답을 만들었다.

**5. 내부 Entity 를 LLM 에 그대로 노출하면 안 된다**

Spring AI 를 실무에 적용한다면 가장 큰 우려는 **민감정보 노출** 이다. prompt 로 *"노출하지 마라"* 적어도 LLM 이 안 듣거나 할루시네이션에 빠지면 위험하다. 답은 **내부 Entity 를 Tool 반환 타입으로 쓰지 말고 *LLM 노출 전용 필드만 가진 View 타입* 을 따로 만드는 것**. 우리 `OrderDetailView` 가 `Order` 의 *주소·라이더 위치·취소 사유* 를 의도적으로 뺀 게 정확히 그 적용이다.

**6. Spring AI 의 예외 처리는 *예외 대신 결과 값***

`exception` 을 그대로 `throw` 하면 LLM 이 결과를 받지 못해 자연어 응답이 불가능하다. 그래서 *예외가 될 수 있는 상황* 을 미리 결과 타입으로 정의해야 한다. 우리는 `Outcome` enum 4종(`NOT_FOUND` / `NOT_CANCELABLE` / `ALREADY_CANCELED` / `CANCELED`)으로 표현했다. GPT-5.5 코드를 리뷰하면서 `IllegalArgumentException` 을 그냥 throw 하는 게 왜 위험한지 직접 확인하니 이 부분이 더 명확해졌다.

**7. 토큰 비용 — Tool 호출은 누적된다**

아직 토큰 비용 감각이 완전히 잡히진 않았지만, **실제 서비스라면 트래픽 관리 이상의 관리 포인트** 가 될 거란 감은 잡혔다. 이번 라운드에서 검증한 것: Tool 호출 한 번에 토큰 2배 (1차 컨텍스트가 2차 호출에 누적) → N회 호출 시 *선형이 아닌 누적적* 증가. Tool 정의 자체도 매 호출 system prompt 와 함께 전송되는 비용. **prompt 를 컴팩트하게 작성하는 것** 이 운영 측면에서 중요하다.

**8. 앞으로 Observability 가 가장 중요할수 있다**

AWS Summit 의 WhaTap Observability 발표를 보고 든 생각 — 기존 시스템은 *명확한 error code* 가 있는데, **agent 시스템은 LLM 이 헛소리로 응답해도 HTTP 200 OK** 라 모니터링이 어렵다. 그런데 advisor 의 비용·응답 시간 측정 자체를 *오류 신호* 로 쓸 수 있을 것 같다 — 비용이 비정상적으로 크거나 작거나, 응답 시간이 이상하게 흘러가면 agent 오류 가능성 의심. 앞으로 다양한 Observability 기능이 agent 영역에서 활성화될 것 같다.

**9, LLM 의 결함은 *도메인 디테일***

요즘 바이브 코딩 시대에 개발자 필수 역량으로 **도메인 지식** 이 많이 꼽힌다. GPT-5.5 에게 *"배달 주문 취소 Tool 만들어줘"* 시켜보니 코드는 어느 정도 잘 만들지만 *멱등성·예외 throw·Outcome 케이스 부족* 같은 운영 디테일을 놓쳤다. **AI 가 기본 구조는 잘 잡아도 도메인 특성에 맞는 디테일은 개발자가 꼭 채워야 한다** 는 걸 직접 확인했다.

### 의문점

**1. Tool 동시 호출 시 트랜잭션·동시성**
같은 주문에 LLM 이 `cancelOrder` Tool 을 동시에 두 번 호출하면 어떻게 되지? `isCancelable()` 체크와 `cancel()` 사이의 race condition 을 막으려면 비관적 락 (`@Lock`) 과 낙관적 락 (`@Version`) 중 어느 게 LLM 환경에 더 맞을까.

**2. `qwen2.5` 의 Tool 호출 불안정성 — 모델 한계인가 prompt 한계인가**
시나리오마다 호출률이 20~100% 로 들쭉날쭉했고, 특히 *실제 상태 변경* 일수록 회피 경향이 강했다. GPT-4·Claude 같은 큰 모델에서도 같은 패턴인지, 아니면 *우리 prompt 가 부족했던 것* 인지 직접 측정해 본 적이 없다. 그리고 **만약 모델 한계로 판명되어 모델을 교체한다면 — 1~3단계의 모든 테스트(시나리오 5종 호출·멱등성 ablation·description A/B/C 정량 비교)를 처음부터 다시 돌려야 할까?** 우리 평가축인 *"이 prompt 가 안전한가"* 의 결론은 모델에 종속적이라, 모델 교체 시 *어디까지 재검증* 이 필수인지가 운영 관점의 큰 의문이다.

**3. Tool description 토큰 비용 vs 호출률 — 최적점은 어디인가**
3단계 결과 — A 상세(~150 토큰) 5/5, B 빈약(~10 토큰) 3/5, C 거짓(~25 토큰) 4/5. 토큰 *15배* 늘려서 호출률 *66% → 100%* 가 정말 효율적인 선택일까? *비용·정확도의 sweet spot* 은 어디인지 모르겠다.

### Round 3 (Chat Memory) 시도 아이디어

**1. 대화가 이어지면 Tool 호출이 더 잘 될까?**
2주차에서 LLM 이 Tool 호출을 안 해서 회피 응답만 주는 경우가 많았다. Memory 가 붙으면 사용자가 *"그래서 어디라고?"* 같이 다시 물었을 때 직전 turn 의 흐름을 LLM 이 같이 보게 될 텐데, 그러면 Tool 호출이 더 잘 발동되지 않을까 싶다. 의문점 b 에서 *모델을 바꾸는 것* 말고도 이런 식으로 풀어볼 수 있을 것 같다.

**2. Memory 가 거짓을 기억해버리면 어떡하지?**
배운점 C 에서 *prompt 는 사이드이펙트에 예민하다* 고 적었는데, Memory 가 붙으면 이게 더 무서워질 것 같다. LLM 이 한 번 *"환불 처리됩니다"* 같은 가짜 약속을 하면 Memory 에 그게 그대로 남는다. 다음 turn 에서 사용자가 환불 얘기를 다시 꺼내면 LLM 은 *"아까 처리된다고 했으니까"* 라며 거짓을 진실처럼 굳혀버릴 위험이 있다. 어느 시점부터 Memory 에 거짓이 쌓이기 시작하는지 직접 보고 싶다.

**3. 대화가 길어지면 비용은 얼마나 늘까?**
이번 라운드에서 Tool 호출 한 번이 토큰 2배가 든다는 걸 봤는데, Memory 가 붙으면 매 turn 마다 *과거 대화 전체* 가 같이 전송될 것 같다. 그럼 대화가 길어질수록 비용이 빠르게 커질 텐데, 그렇다고 오래된 대화를 잘라내면 *"아까 취소한 주문 어떻게 됐어?"* 같은 질문에 시스템이 그 사실 자체를 잊어버려서 멱등성도 깨질 것 같다. **비용을 줄이려는 시도가 멱등성을 깨는 자리** 가 어디인지 찾고 싶다.

---

# Round 3 — 대화 맥락 관리와 메모리 설계

> 한 줄 메시지: **대화 메모리는 "있으면 좋은 기능"이 아니라 상담 에이전트의 전제 조건이다.** Memory 없는 에이전트는 "그거 취소해줘"의 *그거* 를 모르는 단발 챗봇일 뿐.

## 1단계 — Memory 3레이어 + 세션 분리

`ChatMemoryConfig` 3빈 (`InMemoryChatMemoryRepository` / `MessageWindowChatMemory(20)` / `MessageChatMemoryAdvisor(order=10)`) + `SessionController` (`/api/v1/session` — 메시지 조회·clear·세션 목록) + `AssistantController` 에 `X-Session-Id` 헤더 → `ChatMemory.CONVERSATION_ID` 주입.

### 시나리오 5종 (`POST /api/v1/assistant`)

| # | 의도 | 기대 | 실제 | 판정 |
|---|---|---|---|:---:|
| S1 | Memory 기본 (`live-demo`, 2턴) | "그거"→직전 1234 | "그거"→1234, USER×2/ASSISTANT×2 누적 | ✅ |
| S2 | 지시 대명사 우선순위 (1234→1235→"아까 그거") | 마지막(1235) | **1234**(처음) + Tool JSON 텍스트 누출 | ⚠️ |
| S3 | **세션 분리 ★** (A=1234, B=1239, A="그거") | A·B 0 오염 | A엔 1234만 / B엔 1239만, "그거"→1234 | ✅ |
| S4 | clear 후 망각 | clear 후 빈값 | `[]` + "주문번호 알려주세요" 되물음 | ✅ |
| S5 | **default 폴백 보안 ★** (헤더 없이 2명) | 대화 섞임 | 고객2 에게 고객1 의 1234 노출 | ✅ (사고 재현) |

→ **세션 분리 평가축은 S3(격리)↔S5(누출) 대비로 충족.** 같은 코드인데 `X-Session-Id` 헤더 유무가 개인정보 사고를 가른다 — *"테스트(S3)는 통과하고 운영(S5)에서 터지는 사고"*.

### 핵심 발견

1. **Memory 검증과 Tool 검증은 별개 축** — S1 에서 "그거"→1234 는 풀렸지만(Memory ✅), 그 1234 로 Tool 은 안 부르고 위치를 환각했다(Tool ✗). Memory 작동 ≠ Tool 호출.
2. **Tool 호출률 정량 측정** — 동일 질문 10회: chat(memory X) 2/10 vs assistant(memory O) 3/10. → **memoryAdvisor 가 Tool 을 깨뜨린다는 가설 기각** (당초 단발 비교로 "범인"이라 단정했다가 표본 늘려 정정).
3. **"역삼역" 응답 ≠ Tool 호출** — 환각으로도 역삼역이 나옴. 결정적 지표는 `[Tool]` 로그뿐 (Round 2 3단계에서 쓴 지표의 자기수정).

### 상세 보고서

- [Memory + 세션 분리](reports/week3/stage1/memory-and-session-report.md) — 시나리오 5종 raw 응답 + Memory 상태 JSON + 가설 정정 과정 + 설계 결정(MAX_MESSAGES/order/default 폴백/세션 식별 4전략)

## 2단계 — `MAX_MESSAGES` 정량 비교 + 회귀 ablation

### `MAX_MESSAGES` 2 / 20 / MAX_VALUE (평가축 ★)

| 값 | 입력 토큰 추세 | 먼 지시대명사(`2024-1237` 복창) |
|---|---|:---:|
| **2** | ~3600 **평평** (누적 안 됨) | ✗ 되묻기 |
| **20** | 3634→4090 **우상향** 후 상한 | ✅ |
| **MAX_VALUE** | 3636→4093 **우상향** | ✅ |

→ 윈도우가 비용↔맥락 trade-off 를 조절. **2는 토큰 싸지만 맥락 손실, 20+는 토큰 쓰는 대신 맥락 유지. 20 이 sweet spot.**

### 회귀 ablation — `[대화 맥락 사용 규칙]` 이 Tool 을 억제했다

| 조건 | Tool 호출 (assistant 10회) |
|---|---:|
| 5줄(규칙 있음) + temp 0.3 | 3/10 |
| 0줄(규칙 제거) + temp 0.3 | **9/10** |
| 0줄 + temp 0.0 | **10/10** |

→ prompt 한 블록 제거 + temperature 만으로 **20%→100%**. 모델(Q4 7B)은 그대로 — *Tool 불안정은 "모델 탓"이 아니라 prompt·temperature 라는 통제 변수였다.*

### 핵심 발견

1. **prompt 는 전역 확률 분포** — 손대지 않은 Tool 호출을 다른 섹션이 흔든다.
2. **지표 오염 2종 추가** — `1234`(system prompt 예시값)·`9999-0001`(NOT_FOUND 라 LLM 무시) → 깨끗한 측정은 `2024-1237`(유효+비예시).
3. **"모델 탓" 반박** — 통제 변수 고정 전 결론은 성급. (Round 1·2 보고서는 정정하지 않고 사고 진화를 새 보고서로 기록.)

### 상세 보고서

- [MAX_MESSAGES 정량 비교](reports/week3/stage2/max-messages-ablation-report.md) — 토큰·지시대명사 + orderId 지표 오염
- [`[대화 맥락 규칙]` ablation](reports/week3/stage2/context-rule-ablation-report.md) — 회귀 원인 규명 (5줄/4줄/0줄)
- [temperature 테스트](reports/week3/stage2/temperature-tool-calling-report.md) — "모델 탓" 반박

## 3단계 — InMemory vs JDBC 영속화

`spring-ai-starter-model-chat-memory-repository-jdbc` + h2. `@Profile("!jdbc")` 로 InMemory↔JDBC 분리.

### 재시작 실험 (평가축 ★)

동일 2턴 대화 후 **서버 재시작**:

| 저장소 | 재시작 전 | 재시작 후 |
|---|---:|---:|
| **JDBC (`h2:file`)** | 4건 | **4건 유지** ✅ |
| **InMemory** | 4건 | **0건 소실** ❌ |

→ JDBC 는 대화 맥락("그거"→1234)까지 복원. InMemory 는 배포 한 번에 전체 증발.

### 의사결정 트리

```
Q1. 재시작 시 대화가 사라져도 되는가? → YES: InMemory / NO: Q2
Q2. 멀티 인스턴스 배포인가?            → YES: JDBC/Redis / NO: Q3
Q3. 감사·법적 보존이 필요한가?         → YES: JDBC / NO: InMemory + TTL
```

### 핵심 발견 — 함정 5종 연쇄

강의·starter 의 `h2:mem + initialize-schema: embedded` 로는 **재시작 실험이 구조적으로 불가능**. 5개를 차례로 풀어야 동작:

1. h2 classpath → 기본 프로필도 자동구성 충돌 → `exclude`
2. exclude 가 jdbc 프로필에 상속 → `exclude: []` override
3. Spring AI 1.0.0 에 `schema-h2.sql` 없음 → `platform: postgresql`
4. `h2:mem` 은 재시작 소실 → `h2:file`
5. file 은 embedded 판정 밖 → `initialize-schema: always`

(영속화 = 개인정보 처리자가 되는 결정 — content 평문 저장·TTL 부재가 우리 현재 위반점.)

### 상세 보고서

- [JDBC 영속화 + 재시작 + 의사결정 트리](reports/week3/stage3/jdbc-persistence-report.md) — 함정 5종 상세 + 테이블 스키마(TOOL 미저장) + **H2 Console 쿼리 결과**(conversation_id·timestamp 컬럼) + 개인정보 리스크

## 4단계 — Observability + AI 코드 리뷰

### 토큰 누적 + Memory 주입

10턴 입력 토큰 **3559 → 4093** 단조 증가(턴당 ~53), T8~10 에서 ~4090 정체(MAX=20 윈도우 상한). 2회차 프롬프트 전문에서 **SYSTEM 앞뒤로 1회차 USER+ASSISTANT 가 주입**된 것 확인 — "그거"→1234 해석의 실물. (TOOL 메시지는 미적재.)

### AI 코드 리뷰 — codex 멀티턴 챗봇

codex 에 순진한 프롬프트로 받은 코드(우리 프로젝트 밖에서 생성). **Round 2 GPT-5.5 보다 완성도 높음** — conversationId 세션분리·maxMessages=20·@Valid·ChatClient 빈 1회를 이미 갖춤. 남은 결함 3종이 *운영에서야 드러나는 판단*:

| # | 결함 | 우리 Round 3 실증 |
|---|---|---|
| 1 | 세션 `"default"` 폴백 (conversationId 옵션 body) | 1단계 S5 누출 |
| 2 | InMemory 영속성 없음 (repository 미지정) | 3단계 재시작 4건→0건 |
| 3 | Observability 부재 (토큰/시간 로깅 없음) | 4단계 토큰 3559→4093 추적 불가 |

→ AI 코드 결함이 *문법 오류*에서 *"운영에서야 터지는 미묘한 판단"*으로 이동. **실패를 직접 재현·측정해 본 사람만이 이 결함을 짚을 수 있다.**

### 상세 보고서

- [Observability + AI 코드 리뷰](reports/week3/stage4/observability-and-ai-review-report.md) — 10턴 토큰표 + 2회차 프롬프트 전문 + codex 결함 3종 개선 코드

---

## 공통 — 학습 기록

### 내가 배운 것

**1. prompt 는 전역 확률 분포다 — 한 섹션이 다른 섹션을 흔든다**

`[대화 맥락 사용 규칙]` 5줄이 prompt 에 있을 때 Tool 호출률이 20%, 빼니 80%였다. 지시 대명사 해결을 도우려던 규칙이 *손대지도 않은* Tool 호출을 억제한 것. prompt 한 블록이 그 블록만의 효과로 끝나지 않고 **모델의 전체 응답 확률을 흔든다**. (ablation 으로 확정) — Round 2 배운점 *"prompt 는 사이드이펙트에 예민하다"* 가 Memory 라운드에서 더 강하게 재현됐다.

**2. 응답 텍스트를 측정 지표로 쓰면 오염된다 — 세 번 데였다**

Tool 호출됐나 보려고 "역삼역"이 응답에 있나 셌는데, Tool 안 불러도 환각으로 역삼역이 나왔다. 지시 대명사 보려고 "1234" 복창을 봤더니 system prompt 예시값이라 Memory 가 비어도 1234가 나왔다. 안 쓰는 번호 `9999-0001` 로 바꿨더니 이번엔 NOT_FOUND 라 LLM 이 무시했다. → **응답 텍스트는 환각·예시·에러에 오염된다. 믿을 건 `[Tool]` 로그뿐.** Round 2 3단계에서 "역삼역 포함 횟수"를 성공 지표로 썼던 게 사실 과대평가였다는 자기수정.

**3. "모델 탓"은 통제 변수를 고정하기 전엔 성급한 결론**

Tool 이 불안정하길래 "qwen2.5 가 약해서"라고 결론냈는데, prompt(5줄 제거) + temperature(0.0) 둘만 조정하니 *같은 모델로* 100%가 됐다. 모델은 그대로인데 20%→100%. **변수를 다 고정하기 전에 모델을 탓한 게 게을렀다.** Round 2 의문점 b *"모델 한계인가 prompt 한계인가"* 에 대한 잠정 답 — 적어도 이 케이스는 prompt·temperature 였다.

### 의문점

**Tool 응답을 Memory 에 넣으면 LLM 행동이 어떻게 달라질까?**

지금은 USER/ASSISTANT 만 저장하고 TOOL 메시지는 안 남긴다. 그래서 "그거"를 풀려면 ASSISTANT 응답 본문에 orderId 가 있어야 한다. 만약 Tool 응답(JSON 전체)까지 Memory 에 넣으면 — 맥락이 더 정확해질까, 아니면 토큰만 폭증하고 LLM 이 raw JSON 에 휘둘릴까? Spring AI 가 USER/ASSISTANT 만 적재하는 게 *기본값*인 이유를 직접 깨보고 싶다.

### Round 4 (RAG) 아이디어

**Memory + RAG advisor 공존**

Memory 는 "그 주문" 같은 세션 맥락, RAG 는 "비 오는 날 배달 지연 보상 정책" 같은 지식. 두 advisor 가 체인에 같이 붙으면 *"아까 그 주문, 비 와서 늦었는데 보상 되나요?"* 같은 질문을 커버할 수 있을 듯하다. order 순서(Memory 먼저냐 RAG 먼저냐)가 설계 포인트일 것 같다 — 3단계에서 advisor order(memory 10 < performance 100)를 직접 본 게 여기로 이어진다.
