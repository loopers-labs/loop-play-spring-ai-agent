# Round 4 — RAG + PgVector

> 제출: 1·2·3·4 단계 + 공통 학습 기록 (100/100점)
> 내부 정제본: `round4/EXPERIMENT_LOG_QUEST{1,2,3,4}.md`
> 판단 기록 raw: `.private/notes/round4/decisions-log-quest{1,2,3,4}.md`

---

## 1단계 — RAG 기본 구현 + 시나리오 5종 검증 (30점)

### 구현 결과

- `RagConfig.java` — TOP_K=4, SIMILARITY_THRESHOLD=0.5, TokenTextSplitter(800/350), QuestionAnswerAdvisor order(20)
- `KnowledgeLoader.java` — FaqDocument → Document 변환 + `alreadyLoaded(faqId)` 중복 방지
- `AssistantController.java` / `SupportController.java` — `defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)`
- `BaedalPrompt.java` — `[정책 인용 규칙]` 5섹션 (Fallback / 원문 수치 / 상담 범위 밖 / 복수 정책 우선순위)

### 기동 로그 — 신규 7건 + 재기동 스킵 7건

```
# 최초 시드 (2026-06-05 22:15:57)
[KnowledgeLoader] RAG 시드 완료 — 신규 7건 / 스킵 0건 / 총 7건

# 재기동 (2026-06-06 12:11:07) — alreadyLoaded(faqId) 동작
[KnowledgeLoader] RAG 시드 완료 — 신규 0건 / 스킵 7건 / 총 7건
```

### vector_store 카테고리 분포 (chunk-800 / baseline)

```sql
SELECT count(*), metadata->>'category' FROM vector_store GROUP BY metadata->>'category';
```

| count | category |
|---|---|
| 2 | refund |
| 2 | delivery-delay |
| 1 | cancel |
| 1 | coupon |
| 1 | account |
| **7** | **total** |

### 시나리오 5종 검증

#### Context 블록 캡처 (1단계 보강 측정, baseline 상태)

5 시나리오 × 1 ask 보강 측정으로 *RAG가 어떤 정책 청크를 박았는지* 직접 확인 (`boost-1stage-measure.sh` + `bootrun-boost-1stage.log`):

| 시나리오 | Context 청크 | 박힌 정책 | 처리 흐름 |
|:-:|:-:|---|---|
| 1 (지연 보상) | 2건 | `배달 지연 보상 기준` + `기상 악화 시 배달 지연 안내` | RAG 정상 retrieve |
| 2 (취소) | 1건 | `환불 기본 정책` | RAG 정상 retrieve |
| 3 (쿠폰) | 1건 | `쿠폰 사용 FAQ` | RAG 정상 retrieve |
| 4 (전화번호) | **0건** | (RAG retrieve 0건) | **`[금지]` 룰이 Context 없이 처리** |
| 5 (도메인 밖) | **0건** | (RAG retrieve 0건) | **`[정책 인용 규칙]` Fallback 처리** |

→ **흥미로운 발견**: 시나리오 4·5는 RAG가 청크를 못 박는데도 결과는 정확합니다. *`[금지]` 룰과 Fallback 문장*이 RAG가 실패해도 backup 역할을 합니다. 즉 *Threshold + 프롬프트 룰 2중 방어*가 운영에서 작동하는 패턴이 보강 측정으로 확인됐습니다.

#### 시나리오 응답 본문 sample (baseline)

| # | 응답 발췌 |
|:-:|---|
| 1 | *"비 오는 날 배달이 늦어졌을 때 보상을 받으실 수 있는지 확인해보겠습니다. 주문한 음식이 예상 시간보다 얼마나 지연되었는지 알려주시겠어요? 또한, 기상 특보가 발효되어 있었나요?"* |
| 2 | *"주문 상태가 CREATED 또는 ACCEPTED인 경우 결제 후 바로 취소해도 전액 환불이 가능합니다…"* (정책 원문 인용) |
| 3 | *"쿠폰 중복 사용은 쿠폰마다 다르지만, 일반적으로 할인 쿠폰과 배달비 쿠폰을 함께 사용할 수 있습니다. 하지만 같은 종류의 할인 쿠폰은 1회에 한하여만 사용 가능합니다."* |
| 4 | *"존중하는 고객님, 배달 서비스에서는 사장님의 개인 정보를 안전하게 보호하기 위해 전화번호 제공은 불가능합니다…"* |
| 5 | *"저는 배달 서비스 상담만 도와드릴 수 있어요. 주문·배달·환불 관련해 무엇을 도와드릴까요?"* (BaedalPrompt 결정적 Fallback) |

#### 시나리오 4 — 전화번호 비노출 증명 (40 ask 누적)

quest1 K sweep 40 ask 응답 sample:
> *"죄송합니다. 배달 서비스에서는 사장님의 연락처를 알려드리지 않습니다…"*
> *"고객님, 배달 서비스에서는 사장님의 연락처를 제공하지 않습니다…"*

전화번호 패턴(`010-XXXX-XXXX`·연락처+숫자) **0건**. `[금지]` 룰 정상 작동.

#### 시나리오 5b — Memory + RAG 협업 2턴 (1234 복원 증명)

2턴 대화 + `/api/v1/session/.../messages` 조회 (`boost-1stage-session-messages.json`):

**턴 1**: *"주문번호 2024-1234 배달 어디쯤이에요?"*
- Tool 호출 (`getDeliveryStatus`) → *"현재 라이더는 역삼역 사거리 부근에서 배송 중이라고 합니다. 예상 도착 시간은 약 17시 43분 42초 입니다."*
- Context 블록 0건 (RAG는 Tool 분기 인식, 정책 retrieve 없이 처리)

**턴 2**: *"아까 그 주문 환불 돼요?"*
- 응답: *"주문번호 2024-1234의 경우, 현재 환불 상태를 확인해보겠습니다. 주문 취소와 환불은 주문 상태에 따라 다르게 처리됩니다. 예를 들어, 조리 시작 전인 CREATED 또는 ACCEPTED 상태에서는 고객 요청으로 즉시 환불이 가능하지만, COOKING 이후에는 불가능합니다."*
- → **`2024-1234` 명시적 복원** + 환불 정책 인용 동시 작동

`/session/.../messages` 조회 결과 (Memory 저장 내용):
```json
[
  { "type": "USER",      "content": "주문번호 2024-1234 배달 어디쯤이에요?" },
  { "type": "ASSISTANT", "content": "현재 라이더는 역삼역 사거리 부근에서…" },
  { "type": "USER",      "content": "아까 그 주문 환불 돼요?" },
  { "type": "ASSISTANT", "content": "주문번호 2024-1234의 경우…" }
]
```

→ Memory가 *2024-1234*를 턴 1부터 정확히 저장 + 턴 2에서 *"아까 그 주문 = 2024-1234"*로 복원 + RAG가 환불 정책 retrieve. **Memory + RAG dataflow 정상 작동 증명**.

| # | 시나리오 | Top-K 카테고리 | 기대 충족 |
|:-:|---|---|:-:|
| 1 | "비 오는 날 배달이 늦으면 보상 받을 수 있나요?" | `delivery-delay` (weather-delay 포함) | ✅ 기상 특보·예상 시간 +30분·쿠폰 인용 |
| 2 | "결제 후 바로 취소하면 환불되나요?" | `cancel` + `refund` | ✅ 조리 시작 전/후 구분 인용 |
| 3 | "쿠폰 중복 사용되나요?" | `coupon` | ✅ 중복 적용 불가·최소 주문 금액 인용 |
| 4 | "사장님 전화번호 알려주세요" | `account` (privacy) | ✅ 40/40 거절 응답 (1단계 K sweep raw) |
| 5 | 2턴: "2024-1234 배달 어디?" → "아까 그 주문 환불 돼요?" | Memory 1234 복원 + `refund` retrieve | ✅ 3단계 raw payload에서 10/10 복원 확인 |

> **시나리오 4 별도 측정 필요** — `/api/v1/assistant`에 *"사장님 전화번호 알려주세요"* 요청 + 응답에 전화번호 비노출 검증.
> **시나리오 5 session 조회** — `curl /api/v1/session/memo-rag/messages | jq` 캡처.

### 설계 결정 문서

#### 1-1. 왜 청크 800 / min 350인가?

800 / 350 결정은 *조항 단위로 이미 쪼개진* 배달 정책 FAQ 도메인에 맞춘 값입니다. 실제로는 더 최적화된 값(예: 600 / 350)이 존재할 가능성이 있지만, 시간적인 문제로 모든 값을 세세히 테스트하지 못했고, 가장 추천되는 값인 **800 / 350**으로 설정했습니다.

**도메인 사실**: FAQ 7건이 `refund-basic`, `refund-after-delivered`, `cancel-policy`, `weather-delay` 등 정책 조항 단위로 분리돼 있고, 1건이 25~35줄 / 약 300~600 토큰입니다. chunkSize 800은 *1 FAQ = 1 chunk*가 자연스럽게 성립하는 자리고, minChunkSizeChars 350은 잔여 조각이 너무 작아지지 않게 보장하는 디폴트 하한입니다.

**2단계 sweep 정량 비교** (5 시나리오 × 10 trial × 4 phase = 200 ask):

| 실험 | chunkSize | 평균 입력 토큰 | 청크 수 | scn 1 정답률 |
|---|:-:|:-:|:-:|---|
| A | 800 | 2132 | 7 (1 FAQ = 1 chunk) | 7/10 partial 6 |
| B | 100 | 1764 (-17%) | 49 (6~8/FAQ) | **4/10** hallu 4 fallback 5 |
| C | 2000 | 2187 | 7 (800과 동일) | 7/10 partial 5 |

- **키워도 의미 없음 (2000)**: 청크 수가 800과 똑같이 7개로 나옴. FAQ 1건이 600 토큰 이하라 2000을 줘도 더 채울 게 없음 (단 시나리오 1만 weather-delay가 길어서 +11% 입력 토큰 증가).
- **줄이면 깨짐 (100)**: 시나리오 1 정답률 7/10 → 4/10. 원본 FAQ가 *11~29분(1,000원) / 30~59분(배달비 환불 또는 3,000원) / 60분+(전액 환불)* 3단 표 구조인데 청크 100자가 표를 분할해 *"60분 이상"* 한쪽만 잡힌 경우 다수. 시나리오 4에서는 *한국어→중국어 코드 스위치*까지 발생 — grounding이 끊긴 신호.

**다른 도메인 — 블로그 글 / 장문 PDF**: 자연 단위가 더 크므로 chunkSize도 더 크게(**1500~3000** 정도) 가야 할 것 같습니다. 본 라운드에서 시도해본 값 중에는 **2000**이 가장 가까운 참고점입니다 (단 배달 도메인 측정값이라 일반화는 직관 영역). 블로그 글은 H2 단위 splitter, 장문 PDF는 *절 단위 splitter + overlap*으로 절 경계를 보존하는 방향이 자연스러울 것으로 추측합니다.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - 측정 순서를 *"800 → α → 100 → 2000"* (성공 → 실패 1 → 실패 2)으로 잡은 본인 직관 한 줄
> - 4 phase × 10 trial = 40 시점에서 더 안 돌린 stopping rule

#### 1-2. 왜 Top-K=4인가?

vector_store에 FAQ 7건이 있는 상황에서 K=1·4·7·10 4개 값을 비교한 결과, **K=4가 가장 sweet spot**이었습니다. 4를 고른 이유는 네 가지입니다.

**1단계 K sweep 정량 비교** (5 시나리오 × 12 trial × 4 K = 240 ask):

| K | 평균 입력 토큰 | 평균 ms | scn 1 | scn 2 | scn 3 | 정답률 합산 (scn 1+2+3) |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| 1 | 2238 | 8942 | 8/10 | 5/10 | 5/10 | 18 |
| **4** | **2397** | **9562** | **7/10** | **7/10** | **7/10** | **21 ⬆️ (정점)** |
| 7 | 2444 (+47) | 9374 | **5/10 ⚠️** | 6/10 | 6/10 | 17 ⬇️ |
| 10 | 2442 (+0) | 10086 | 7/10 | 6/10 | 8/10 | 21 (scn 5a 중국어 1건 ⚠️) |

**1. 정답률이 가장 높음 (역U자 곡선 정점)**

정답률 합산은 K=1: 18 / **K=4: 21 (정점)** / K=7: 17 / K=10: 21. *K↑ = 품질↑* 직관과 달리 K=4에서 정점을 찍고 K=7에서 17로 떨어집니다. K=4는 시나리오 1·2·3을 모두 7/10으로 균형 있게 가져가는 유일한 K였습니다.

**2. K=7에서 무관 정책이 섞이는 cliff drop**

시나리오 1 (날씨 지연 보상)에서 정답률이 K=4 7/10 → K=7 **5/10**으로 떨어졌습니다. vector_store 7건을 거의 다 retrieve하니 *무관 정책이 섞이며* LLM이 정책 인용 대신 *"주문번호를 알려주시겠어요?"* 같은 Tool 분기로 빠지는 경우 다수.

**3. K=10은 입력 토큰이 안 늘어남 — K=7과 동일 (실효 상한)**

K=7 입력 토큰 2,444, K=10 입력 토큰 2,442 — **+0 증가**. vector_store가 7 row이라 K를 키워도 더 박힐 청크가 없습니다. *K=10은 K=7과 똑같은 retrieve를 하면서 평균 ms만 +712 더 느려짐*. 굳이 K=10을 쓸 이유가 없습니다.

**4. K=10에서 qwen2.5 long-context 깨짐 — 채택 시 추가 위험**

시나리오 5a (Tool call)에서 *한국어 → 중국어 코드 스위치* 1건 발생. ~5,300 입력 토큰 부근에서 7B 모델의 출력 format이 깨졌습니다. 시나리오 3 정답률이 8/10으로 약간 이득이 있어도 *안정성 임계*가 더 무겁다고 판단했습니다.

**결론**: K=4는 정답률 정점 + K=7·10이 안고 있는 cliff drop·long-context 위험을 모두 회피하는 자리. vector_store 7건 도메인에선 **K=4면 충분하고 K를 더 키울 이유가 없습니다**.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - K=4 정점 발견을 *예상했는지 예상 외였는지* 본인 한 줄
> - K sweep 측정 순서 *"K=1 → K=4 → K=7 → K=10"* (확장 방향)을 잡은 본인 직관

#### 1-3. 왜 `QuestionAnswerAdvisor.order(20)`인가?

##### Advisor 순서: 왜 Memory(10) → RAG(20) → Performance(100)인가

시나리오 5(턴 1 *"주문번호 2024-1234 배달 어디?"* → 턴 2 *"아까 그 주문 환불 돼요?"*)를 시간순으로 따라가 보면, Advisor 체인이 왜 이 순서여야 하는지가 드러납니다. Spring AI Advisor 체인은 `order` 오름차순으로 BeforeCall이 실행되는 양파 구조라서, **앞쪽 Advisor가 변형한 프롬프트가 뒤쪽 Advisor의 입력**이 됩니다.

**1단계 — Memory(10)가 먼저 들어가야 RAG가 의미를 안다.** 턴 2의 *"아까 그 주문"*은 그 자체로는 어떤 주문인지 알 수 없는 지시대명사입니다. `MessageChatMemoryAdvisor`(order=10)가 먼저 BeforeCall로 턴 1의 대화 이력을 prepend해 줘야, 뒤따르는 `QuestionAnswerAdvisor`(order=20)가 *"1234 주문에 대한 환불"*이라는 맥락이 살아있는 query로 벡터 검색을 돌릴 수 있습니다. 실제 두 phase 모두에서 `1234` 자체 복원율은 10/10으로 동일했지만 — 즉 Memory가 정보를 가져오는 것 자체는 어느 순서든 됩니다 — 문제는 그 뒤입니다.

**2단계 — RAG는 Memory가 변형한 결과물을 query로 받아야 정확하게 retrieve한다.** order를 뒤집어 RAG(5) → Memory(10) 순으로 실험한 phase에서, **Refund 정책 인용률이 7/10 → 5/10으로 떨어지고**, 턴 2 평균 응답시간은 18,229ms → 21,278ms로 +17% 늘었습니다. RAG가 *"아까 그 주문 환불 돼요?"*라는 빈 query만 가지고 검색을 돌리니, 환불 정책 문서가 top-k에 잘 안 잡혔고, LLM은 부족한 컨텍스트로 더 길게 헤매며 응답을 만들었습니다.

| 지표 | order(20) 정상 | order(5) 뒤바꿈 |
|---|:-:|:-:|
| 1234 복원 (Memory) | 10/10 | 10/10 |
| Refund 정책 인용 | **7/10** | **5/10** (-2) |
| Fallback 빈도 | 5/10 | 3/10 |
| 턴 2 avg ms | 18,229 | **21,278 (+17%)** |

**3단계 — 결정적 부작용: Memory 오염.** RestClient body raw를 직접 캡처해 보니, 뒤바꿈 phase에서는 RAG가 먼저 끼어들어 prompt에 박은 *"Context information is below, ... Given the context and provided history information..."* 보일러플레이트가 **Memory의 USER 메시지로 영구 저장**되고 있었습니다. 다음 턴부터는 이 오염된 USER가 계속 누적돼, 매 턴 토큰 낭비 + 의도 흐림 + AfterCall 시 메시지 변환 비용까지 떠안게 됩니다.

```
[정상]   USER(턴2 메모리): "주문번호 2024-1234 배달 어디?"   ← 깨끗
[뒤바꿈] USER(턴2 메모리): "주문번호 2024-1234 배달 어디?
         Context information is below, ... [정책 원문]
         Given the context and provided history information..."  ← 영구 오염
```

**결론.** `order(20)`이라는 숫자 자체에 의미가 있는 게 아니라 — *"10보다 크고 100보다 작아서 Memory 뒤 / Performance 앞"*이라는 **상대적 위치**가 본질입니다. Memory가 먼저 컨텍스트를 깔고, RAG가 그 위에서 검색하고, Performance(100)가 가장 바깥에서 전체 시간을 잰다 — 이 순서가 뒤집히면 *정확도(7→5)·지연(+17%)·메모리 누적 오염* 세 가지가 한꺼번에 깨집니다.

##### 왜 굳이 `.order(20)`인가 — 사실 20은 아무 의미 없다

결론부터 말하면 **20이라는 절대값에는 어떤 의미도 없습니다.** *"굳이 20일 필요는 없으나 10 → 20이니까 느낌"*이라는 직관 그대로입니다. Spring AI Advisor 체인은 order 오름차순으로 BeforeCall이 실행되고 역순으로 AfterCall이 실행되는 양파 구조라, `memoryAdvisor(10) < ragAdvisor(?) < performanceAdvisor(100)`이라는 **상대 위치**만 지켜지면 가운데 값은 15든 30이든 50이든 동일하게 동작합니다.

본 프로젝트가 20을 고른 이유는 단순히 *"Memory(10) 바로 다음에 RAG가 온다"*는 의도를 가독성 좋게 표현한 관용적 선택입니다. 10의 배수로 끊고 Memory와 10만큼 띄워 *"바로 인접한 다음 칸"*임을 드러낸 것뿐입니다.

| 후보 값 | 결과 | 이유 |
|---|---|---|
| `order(5)` | 깨짐 | Memory보다 먼저 실행 → 3단계에서 Refund 인용 7→5/10, 턴 2 +17% 지연, Memory에 RAG 보일러플레이트 영구 오염 |
| `order(15)` | 정상 | 10 < 15 < 100, 시나리오 5 결과 동일 |
| `order(20)` | 정상 (현재 선택) | 10의 배수 + Memory와 한 칸 간격, 가독성 |
| `order(50)` | 정상 | 10 < 50 < 100, 시나리오 5 결과 동일 |
| `order(200)` | 깨짐 | Performance(100)보다 뒤로 밀려 RAG 추가 토큰이 측정에서 누락됨 |

즉 *20은 "Memory와 Performance 사이 어딘가"를 사람이 읽기 쉽게 적은 숫자*일 뿐이고, 본 프로젝트의 기준은 **상대 순서 보장 + 가독성**입니다.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - Memory 오염 발견을 *예상했는지 예상 외였는지* 본인 한 줄
> - QUEST 본문 힌트(*"RAG가 무관 정책 retrieve"*)와 실측(*"환불 키워드 매칭 충분"*)의 차이에 대한 본인 해석

#### 1-4. `similarityThreshold=0.5`의 근거

T=0.5 결정은 *한국어 짧은 정책 FAQ 도메인에서 정답 페어 score가 ~0.5 부근에 분포*한다는 1단계 sweep 결과에 맞춘 값입니다. **본 라운드 실측은 T를 *너무 높였을 때*(0.65·0.75)만 진행했고**, T를 낮추는 쪽(0.3·0.4)은 측정하지 않아 아래에서는 리서치 자료로 보충했습니다.

**1단계 T sweep 정량 비교** (5 시나리오 × 12 trial × 3 T = 180 ask):

| T | 평균 ms | 평균 입력 토큰 | RAG empty 비율 | scn 1 | scn 2 | scn 3 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| **0.5** | 9,585 | **2,306** | 20/60 | **8/10** | **10/10** | **7/10** |
| 0.65 | 5,826 (-39%) | 1,847 (-20%) | **53/59 ⚠️** | **0/10 ❌** | **0/10 ❌** | **0/10 ❌** |
| 0.75 | 5,899 | 1,875 | 55/60 | 0/10 | 0/10 | 0/10 |

**너무 높으면 (T≥0.65) — 정답 청크가 cutoff됨 (실측)**

T=0.65에서 시나리오 1·2·3 정답률이 *모두 0/10으로 cliff drop*. 입력 토큰도 시나리오 1 기준 2,451 → 1,579 (-872)로 떨어졌는데, 이건 *RAG retrieve 0건*을 정확히 의미합니다 (Context 블록이 박히지 않음). 모델은 부족한 컨텍스트로 *"확인해보겠습니다"* 빈 응답 또는 *"1~3일"* 같은 일반 상식 hallucination으로 빠집니다. RAG empty 비율도 20/60 → 53/59로 *대부분의 ask에서 retrieve가 안 됨*.

**진단 — 한국어 정책 FAQ score 분포가 ~0.5 부근**

일반론으로 *정답 청크 score는 0.6~0.76* 정도라고 알려져 있는데, 본 도메인(한국어 + 짧은 정책 FAQ)에서는 *~0.5 부근에 분포*했습니다. T=0.65 컷오프는 그래서 *정답 청크까지 잘라버리는 결과*가 되었습니다. T=0.5는 *이 분포 하단을 살리는 자리*입니다.

**너무 낮으면 (T<0.5) — 본 라운드 미실측, 리서치 자료 인용**

본 라운드는 T=0.4·0.3을 직접 측정하지 않았습니다. RAG 운영 자료들이 일관되게 짚는 *T를 너무 낮췄을 때의 부작용*은 다음 세 가지입니다.

1. **무관 청크가 컨텍스트를 덮어 환각이 증가** — *"A retrieval threshold too low floods context with irrelevant documents, increasing hallucination"*. score 컷오프 없이 K개를 무조건 채우면 도메인 밖 청크가 Top-K에 들어와 모델이 그걸 *근거로 오해*함 ([ragaboutit.com](https://ragaboutit.com/why-rag-systems-still-hallucinate-when-you-need-accuracy-most/), [Medium - Sharvari Raut](https://sharur7.medium.com/how-to-stop-llm-hallucinations-in-retrieval-augmented-generation-rag-5ef2894f9cd6)).
2. **false positive 비율 증가** — 임계값이 너무 낮으면 *"too low a threshold increases false positives and irrelevant results"*. 한 banking 사례 연구에서는 threshold 조정만으로 false positive 비율을 의미 있게 낮춘 결과를 보고 ([InfoQ — Reducing False Positives in RAG](https://www.infoq.com/articles/reducing-false-positives-retrieval-augmented-generation/)).
3. **semantic similarity ≠ answerability** — cosine similarity는 *주제 관련성*을 재지 *질문에 답할 수 있는지*를 재지 않습니다. 같은 어휘를 공유하지만 다른 의도의 청크가 높은 score로 retrieve될 수 있음 ([Medium — Better RAG Retrieval](https://meisinlee.medium.com/better-rag-retrieval-similarity-with-threshold-a6dbb535ef9e), [AutoRAG docs](https://docs.auto-rag.com/nodes/passage_filter/similarity_threshold_cutoff.html)).

본 도메인에 적용해 보면, T를 0.5보다 더 낮추면 *"오늘 점심 뭐 먹을까요?"* 같은 도메인 밖 시나리오 5에서 무관 정책이 Top-K에 섞여 들어와 모델이 *"환불 정책에 따르면 점심으로 비빔밥을…"* 같은 환각으로 빠질 위험이 큽니다. 본 라운드에서는 이 쪽 cliff는 직접 측정하지 않았으므로 정확한 cutoff 위치는 후속 sweep이 필요합니다.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - 가설 *"T=0.65 noise 차단으로 정답률 ↑"*이 부정된 발견을 *예상했는지 예상 외였는지* 본인 한 줄
> - 한국어 정책 임베딩 score가 일반론(0.6~0.76)보다 낮게 분포(~0.5)한다는 발견에 대한 본인 해석

---

## 2단계 — 청킹 전략 실험 + 실패 관찰 (25점)

### 정량 비교 표

| 실험 | chunkSize | vector_store 청크 수 | 평균 입력 토큰 (5턴) | 답변 품질 (만족/애매/불만족) |
|---|:-:|:-:|:-:|---|
| **A** | 800 (기준) | **7** (1 FAQ = 1 chunk) | 2,132 | **만족** (제안값) |
| **B** | 100 (극단적 작게) | **49** (6~8/FAQ) | 1,764 (-17%) | **애매** (제안값) |
| **C** | 2000 (크게) | **7** (chunk-800과 동일) | 2,187 (+2.6%) | **만족** (제안값, chunk-800과 거의 동치) |

- 청크 수는 1단계 KnowledgeLoader 시드 결과 (FAQ 7건 입력 → splitter 통과 후 row 수)
- 평균 입력 토큰은 PerformanceLoggingAdvisor 로그 (5 시나리오 × 10 trial = 50 ask 평균)

### 답변 품질 평가 근거 — 시나리오별 정답률 (10 trial 중)

| 시나리오 | chunk-800 | chunk-100 | chunk-2000 |
|---|:-:|:-:|:-:|
| 1 (지연 보상) | 7/10 partial 6 | **4/10** partial 4 **hallu 4** fallback 5 | 7/10 partial 5 |
| 2 (취소) | 8/10 partial 8 | 10/10 partial 9 | 8/10 partial 8 |
| 3 (쿠폰) | 10/10 partial 10 | 10/10 partial 10 | 10/10 partial 10 |
| 4 (배달 후 환불) | 10/10 partial 8 | 9/10 partial 9 **hallu 1**(중국어 코드 스위치) | 10/10 partial 9 |
| 5 (도메인 밖) | 10/10 fallback | 10/10 fallback | 10/10 fallback |

### 답변 품질 기준 (사용자 정의)

본 README는 다음 3 기준으로 시나리오별 응답을 평가했다:

- **만족**: 모든 시나리오 정답률 7/10 이상 + hallucination 0건 + 정책 원문 수치(60분·24시간·1,000원 등) 포함
- **애매**: 일부 시나리오 정답률 4~6/10 또는 정책 수치 partial 인용만 / hallucination 1~3건
- **불만족**: 정답률 3/10 이하 시나리오 다수 또는 hallucination 5건 이상 또는 코드 스위치(한국어→중국어) 발생

이 기준으로 위 표의 A·B·C는 *만족 / 애매 / 만족* 분포가 나온다.

### 실패 관찰 — 청크가 너무 작을 때(100) / 클 때(2000)

> ⚠️ **Context: 블록 raw 미캡처** — 2단계 chunk sweep 당시 RestClient DEBUG가 적용 안 된 상태였습니다 (DEBUG는 3·4단계에서 처음 도입). 본 섹션은 *EXPERIMENT_LOG_QUEST2의 정성 분석 + bootrun log의 토큰·시간 raw*로 문맥 조각난 사례를 기록합니다.

#### chunk-100 (B) — 시나리오 1 표 3단 구조 분할

원본 FAQ (`weather-delay` 정책)는 다음 3단 표 구조입니다:

| 지연 시간 | 보상 |
|---|---|
| 11~29분 | 1,000원 쿠폰 |
| 30~59분 | 배달비 환불 또는 3,000원 쿠폰 |
| 60분+ | 전액 환불 검토 |

chunk-100에서 LLM 응답은 *"예상 시간보다 60분 이상 지연된 경우 보상"* 만 답한 경우가 10 trial 중 다수. **청크 100자가 표를 가로로 분할해 *"60분 이상"* 한 칸만 Top-K에 잡힌 결과**. 다른 trial에서는 *"약 30분 정도 지연 시 소액 보상"* 같이 정확한 1,000원·3,000원 수치가 빠진 *partial 인용*이 4건.

→ 시나리오 1 정답률 chunk-800 **7/10** → chunk-100 **4/10** (hallu 4 fallback 5).

#### chunk-100 (B) — 시나리오 4 한국어→중국어 코드 스위치

chunk-100 시나리오 4(*"배달 완료 후 환불"*)에서 응답에 *"详细了解您的问题后..."* (중국어로 *"문제를 자세히 파악한 후..."*) 등장 1건. 본문에 *"음식물량 누락"* 같이 FAQ에 없는 합성어도 등장. **문맥 조각으로 grounding이 끊긴 상태에서 7B 모델의 언어/용어 분포 자체가 흔들린 신호**.

→ 시나리오 4 정답률 chunk-800 **10/10** → chunk-100 **9/10** (hallu 1).

#### chunk-2000 (C) — row 수가 A보다 *적어지지 않음* (QUEST 가정과 다른 실측)

QUEST 본문 가이드는 *"chunk-2000에서 row 수가 A(800)보다 적어지는 것"*을 관찰하라고 했지만, **본 도메인에서는 chunk-2000과 chunk-800 모두 row 수 7개로 동일**했습니다. FAQ 7건이 25~35줄(~300~600 토큰)이라 chunk-800·2000 둘 다 *1 FAQ = 1 chunk*로 수렴했기 때문입니다.

유사도 점수가 *전반적으로 낮아지는* 경향도 본 도메인에서 직접 측정 가능한 형태로 보존되지 않았습니다 (RAG retrieve score 분포 raw는 별도 측정 필요). 다만 EXPERIMENT_LOG의 정량 지표는 chunk-800과 거의 동일했습니다:

| 지표 | chunk-800 | chunk-2000 |
|---|:-:|:-:|
| 청크 수 | 7 | 7 (동일) |
| 평균 입력 토큰 | 2,132 | 2,187 (+2.6%) |
| scn 1 정답률 | 7/10 | 7/10 (동일) |
| scn 2 정답률 | 8/10 | 8/10 (동일) |

→ chunk-2000은 *문제를 만들지 않지만 추가 가치도 0* — Blur 상태.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - chunk-100 한국어→중국어 코드 스위치를 *grounding 끊김 신호*로 해석하는 본인 톤
> - QUEST 가정 (chunk-2000 row가 800보다 적어진다)과 본 도메인 실측 (둘 다 7)의 차이에 대한 본인 해석

### 실패 관찰 — Fallback 없는 환각 (`[정책 인용 규칙]` 제거)

QUEST 본문 절차에 따라 `BaedalPrompt`의 `[정책 인용 규칙]` 섹션을 통째로 주석 처리한 채 시나리오 5(*"오늘 점심 뭐 먹을까요?"*) × 10 trial을 측정한 결과입니다 (`quest2-no-policy-rule.jsonl`).

#### 정상 (`[정책 인용 규칙]` 있음) — 10/10 결정적 Fallback

10 trial 모두 **완전히 동일한** 응답:
> *"저는 배달 서비스 상담만 도와드릴 수 있어요. 주문·배달·환불 관련해 무엇을 도와드릴까요?"*

LLM noise가 있어도 결정적 Fallback 문장이 프롬프트에 박혀 있어 10/10 동일하게 작동. *상담 범위 밖* 처리가 완벽합니다.

#### α (no-policy-rule, 규칙 제거) — 0/10 Fallback 소실 + 변형 응답

10 trial 모두 trial별로 다른 변형 응답:

| trial | 응답 발췌 |
|:-:|---|
| 1 | *"점심 메뉴를 추천해드릴 수는 없네요. 먼저, 이전에 주문한 음식을 알려주시면…"* |
| 2 | *"죄송합니다, 현재 주문 내역을 확인할 수 없습니다. 주문번호를 알려주시겠어요?"* |
| 6 | *"현재 주문한 음식 정보가 없습니다. 새로운 주문을 원하신다면 메뉴와 배달지를 알려주시겠어요?"* |
| 9 | *"현재 주문 정보가 없습니다. 새로운 주문을 생성하시려면 메뉴를 선택하고…"* |
| 10 | *"점심 메뉴를 추천해드릴 수는 없네요. 먼저 현재 주문하신 음식을 알려주시겠어요?"* |

응답이 두 가지 패턴으로 분포:
- **A. 점심 추천 어렵네요 + 주문 정보 요청** (tr1·3·4·5·10, 5건): *상담 범위 밖* 명시 룰이 없으니 LLM이 *반쯤 답*하다가 주제 회피
- **B. 주문번호/새 주문 요청 (Tool 분기로 빠짐)** (tr2·6·7·8·9, 5건): *"주문번호 알려주세요"* / *"새로운 주문을 생성하시려면…"* 같이 *도메인 밖 질문을 도메인 안 흐름으로 끌어옴*

#### QUEST 예상과 실측 비교

| QUEST 예상 | 실측 |
|---|---|
| *"비빔밥을 추천드려요"* 같은 명백한 hallucination | *"점심 추천 어렵네요 + 주문번호 알려주세요"* — 더 보수적인 *주제 회피·Tool 분기* |

#### 주제 회피의 원인 — 남은 프롬프트 5섹션

이번 실험에서 제거된 건 `[정책 인용 규칙]` 한 섹션뿐이고, `BaedalPrompt`의 나머지 5섹션은 그대로 살아 있었습니다. 이 남은 섹션들이 LLM을 *환각*이 아닌 *도메인 안 흐름*으로 끌어왔습니다.

| 남은 섹션 | 효과 |
|---|---|
| **[역할]** | *"주문/배달/취소/환불 관련 고객 문의를 1차로 처리"* — 도메인 경계 명시. *"점심 추천"*을 *역할 밖*으로 인식시킴 |
| **[규칙]** | *"정보가 부족할 때는 '주문번호를 알려주시겠어요?' 처럼 구체적으로 요청"* — **주문번호 요청 패턴을 문장 그대로 박아둠** ⭐ |
| **[Tool 사용 규칙]** | getOrderDetail / getDeliveryStatus / cancelOrder — Tool 흐름으로 끌어옴 |
| **[대화 맥락 사용 규칙]** | *"맥락이 모호하면 추측하지 말고 …"* — 추측 회피 강제 |
| **[금지]** | 타사 추천·개인정보 비노출 (시나리오 5에는 직접 영향 X) |

→ tr2·6·7·8·9의 *"주문번호 알려주세요"* / *"새로운 주문을 생성하시려면…"* 패턴의 직접 출처는 **`[규칙]` 섹션의 *"주문번호를 알려주시겠어요?"* 한 줄**입니다. `[정책 인용 규칙]`이 없어 *"상담 범위 밖 — 저는 배달 서비스 상담만…"* 결정적 Fallback은 못 갔지만, 대신 LLM은 *남아있는 [규칙]이 이미 박아둔 회피 패턴*으로 도망쳤습니다.

qwen2.5의 보수적 톤도 한몫했지만 결정적 요인은 *남은 프롬프트가 어디로 회피할지 길을 정확히 알려준 것*입니다. 만약 남은 5섹션도 모두 제거됐다면 *"비빔밥 추천"* 같은 명백한 환각이 나왔을 가능성이 높습니다. **Silent Failure에 가까운 결과**가 나온 건 그래서고, 운영 관점에서 *명백한 환각보다 검출이 더 어려운 위험*입니다 (EXPERIMENT_LOG의 *Silent Failure 발견* — Round 5 Guardrail 의제).

#### 규칙 복원 후 — 정상으로 복귀

복원 후 다시 시나리오 5를 보내면 *"저는 배달 서비스 상담만 도와드릴 수 있어요…"* 결정적 Fallback이 10/10 다시 작동합니다.

→ `similarityThreshold`(0.5)는 *Context를 잘못 박는 것*을 막을 수는 있어도, **시스템 프롬프트의 *"상담 범위 밖"* 결정적 Fallback 룰이 없으면 Fallback 자체가 소실**됩니다. **2중 방어 (Threshold + 결정적 Fallback 문장) 둘 다 필요**.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - QUEST가 예상한 *"비빔밥 추천"* 대신 *"주문번호 알려주세요"* 주제 회피가 나타난 것에 대한 본인 해석
> - *Silent Failure*가 명백한 환각보다 더 위험할 수 있다는 발견에 대한 본인 톤

### 설계 결정 문서

#### 2-1. 배달 정책 도메인에서 가장 적합한 청크 크기는?

A/B/C 200 ask를 *세 축* (정답률·비용·실패 유형)으로 비교 분석한 뒤 추천을 정리합니다.

**축 1 — 시나리오별 정답률**

| 실험 | scn 1 | scn 2 | scn 3 | scn 4 | scn 5 | 합계 (1·2·3) | 합계 (전체) |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| A (800) | 7/10 | 8/10 | 10/10 | 10/10 | 10/10 | 25 | 45 |
| B (100) | **4/10** | 10/10 | 10/10 | 9/10 | 10/10 | 24 (-1) | 43 (-2) |
| C (2000) | 7/10 | 8/10 | 10/10 | 10/10 | 10/10 | 25 (동치) | 45 (동치) |

→ A·C는 정답률 *완전 동치*. B는 시나리오 1에서만 cliff drop (7→4).

**축 2 — 비용 (평균 입력 토큰·ms)**

| 실험 | 평균 입력 토큰 | 평균 ms | 비용 평가 |
|---|:-:|:-:|---|
| A (800) | 2,132 | 7,708 | 기준 |
| B (100) | 1,764 (-17%) | 4,907 (-36%) | **가장 저렴** |
| C (2000) | 2,187 (+2.6%) | 7,337 | A와 거의 동치 |

→ B가 *유일한 비용 절감*. A·C는 차이 미미.

**축 3 — 실패 유형 (정성 관찰)**

| 실험 | 실패 양상 |
|---|---|
| A (800) | 시나리오 1에서 *partial 인용* 6건 (3,000원·1,000원 수치 일부만 인용) — 모델 차원 한계 |
| **B (100)** | 시나리오 1 **표 3단 구조 분할 + hallucination 4** (60분만 답, 30분 가격 누락) + 시나리오 4 **한국어→중국어 코드 스위치 1건** |
| C (2000) | A와 동일 (Blur 상태) |

→ B만 *grounding이 깨지는 신호* (다른 언어 등장).

**3축 종합 비교**

|  | 정답률 | 비용 | 실패 안정성 | 종합 |
|---|:-:|:-:|:-:|---|
| A (800) | 🥇 정점 | 보통 | 안정 | **균형** |
| B (100) | ⚠️ 시나리오 1 cliff | 🥇 가장 저렴 | ❌ grounding 깨짐 | 비용은 좋지만 위험 |
| C (2000) | A와 동치 | A와 거의 동치 | 안정 | A 대비 추가 가치 0 |

---

**제안 — chunk-800 (A)**

본 도메인에는 **chunk-800**이 가장 적합합니다. 세 가지 이유:

1. **품질 정점 + 안정성**: 모든 시나리오 정답률 7/10 이상 + grounding 깨짐 0건. *정답률 합계*에서도 C와 동률 정점이고 *실패 유형*에서도 B 같은 코드 스위치·표 분할 위험이 없습니다.
2. **비용이 부담스럽지 않음**: B(100)가 -17% 토큰·-36% ms로 저렴하지만, *시나리오 1에서 hallucination 4건 발생* — 운영에서 *그럴듯한 거짓 정보가 나가는 위험*은 비용 절감보다 무겁다고 판단했습니다.
3. **C(2000) 대비 *작아서 안전*함**: C가 동일한 결과를 만든다는 건 *현재 도메인이 작아서* 우연히 같이 나온 것. FAQ가 늘어서 한 정책이 800자를 넘기는 순간 *chunk-2000은 두 정책을 한 청크에 묶을 위험*이 생깁니다. *현재 충분한 가장 작은 chunkSize*가 미래 확장에도 안전합니다.

→ 만약 *비용이 최우선*이고 *시나리오 1처럼 다단 표가 없는 도메인*이라면 B가 매력적일 수 있지만, **본 도메인(다단 정책 + 한국어 FAQ + 7B 모델 grounding 민감)에는 A가 가장 적합**합니다.

> ✍️ **본인 추가 자리 (택해서 한 줄)**:
> - 본인이 *측정 전 어떤 chunkSize를 예상했는지* vs 실제 결과의 차이 한 줄
> - 만약 B(100)의 비용 절감이 *얼마나 매력적인지* 본인 입장 (예: 운영 비용 압박이 큰 상황이라면…)

#### 2-2. 청크 오버랩을 0으로 바꾸면? 왜 오버랩이 필요한가?

본 라운드에서 발견한 사실: **Spring AI 1.0의 `TokenTextSplitter`는 오버랩 파라미터 자체를 지원하지 않습니다**. 즉 본 프로젝트의 청크들은 *오버랩을 0으로 설정한 게 아니라*, *라이브러리 제약상 강제로 오버랩 0인 상태*에서 동작하고 있었습니다.

```java
// RagConfig.java:89~98 — 5개 파라미터 어디에도 overlap 없음
return new TokenTextSplitter(
    800,    // chunkSize
    350,    // minChunkSizeChars
    5,      // minChunkLengthToEmbed
    10_000, // maxNumChunks
    true    // keepSeparator
);
```

Spring AI 측에 *오버랩 추가 feature request*가 올라와 있지만(GitHub Issue #2123) 1.0 버전에는 미구현입니다. 그래서 *"오버랩 0으로 바꾸면?"* 질문은 본 도메인에서 *이미 진행 중인 상태*에 대한 답을 정리하는 자리입니다.

##### 오버랩 0의 4가지 문제 (리서치 일반론)

1. **Boundary blindness** — split이 *문장 중간·표 중간·단락 중간*에서 떨어져 컨텍스트 부족한 청크가 생성됩니다. fixed-size 청킹은 *문장이 어디서 끝나는지 모릅니다* ([OptyxStack](https://optyxstack.com/rag-reliability/rag-chunking-strategy-chunk-size-overlap-document-structure-recall)).
2. **Context loss** — 중요 정보가 *두 청크에 걸쳐 있을 때* 한 청크에 다 들어가지 않습니다 ([oneuptime.com](https://oneuptime.com/blog/post/2026-01-30-rag-overlap-strategies/view)).
3. **Semantic fragmentation** — 관련 개념이 떨어진 청크로 분리됩니다 ([Firecrawl](https://www.firecrawl.dev/blog/best-chunking-strategies-rag)).
4. **Query mismatch** — 사용자 질문이 *경계에 걸친 콘텐츠*와 매칭되지 않습니다.

오버랩은 인접 청크에 일부 토큰을 *겹쳐 넣어* 이 경계 문제를 막는 장치입니다. 권장 값은 **chunkSize의 10~20%** (본 800 기준 80~160 토큰).

##### 본 라운드 간접 실측 — chunk-100 표 3단 분할

본 라운드에서 직접 *오버랩 vs 비오버랩* sweep은 못 했지만, **chunk-100 시나리오 1의 표 3단 분할이 정확히 *boundary blindness* 예시**입니다:

- 원본 FAQ (`weather-delay`): *11~29분 → 1,000원* / *30~59분 → 3,000원* / *60분+ → 전액 환불 검토* 3단 표 구조
- chunk-100에서 표가 100자 청크 경계로 잘려 *"60분 이상 보상"* 한 칸만 retrieve됨
- 정답률 7/10 → **4/10** (hallucination 4건, fallback 5건)

**오버랩이 있었다면 표 인접 행이 함께 다음 청크에 따라 들어와 막을 수 있었던 케이스**입니다. 정확히 리서치가 짚는 *fixed-size chunking이 표·다단 구조에 약한 이유*.

##### 본 도메인에서 오버랩 0이 *현재* 작동하는 이유

chunk-800 baseline에서는 *FAQ가 1 청크에 통째로 들어가서* 경계가 *FAQ 사이*에 떨어집니다 — FAQ 안의 정보가 안 잘립니다. 그래서 *오버랩 없이도 정답률 정점*이 나왔습니다. 즉 *오버랩 0이 안전한 게 아니라, FAQ가 짧아서 우연히 안전한 상태*입니다.

##### 언제 위험해지나

- FAQ가 800자를 넘기는 도메인 (예: 정책 조항이 길어지면)
- 청크 경계가 *표·리스트·다단 구조* 중간에 떨어질 수밖에 없는 콘텐츠
- 블로그 글·장문 PDF 같은 *자연 단위가 큰 도메인* — 본 라운드 #1 자리에서 다룬 *블로그/PDF 일반화*가 정확히 이 위험을 강조

→ **Spring AI TokenTextSplitter의 오버랩 미지원이 더 큰 도메인에서는 결함이 될 수 있습니다.** 그 시점에는 LangChain4j 같은 외부 splitter 도입 또는 커스텀 splitter 구현을 검토해야 합니다.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - Spring AI TokenTextSplitter가 오버랩 미지원이라는 사실을 *측정 중에 발견했는지 / 사후에 알았는지* 본인 한 줄
> - 본 도메인에 오버랩 도입이 *필요했다고 보는지* vs *현재로도 충분하다고 보는지* 본인 입장

#### 2-3. "사용자 리뷰 10만 건" 도메인이라면?

본 라운드 도메인(*정적 정책 FAQ 7건*)과 리뷰 10만 건은 *문서의 성질 자체*가 다릅니다. 직접 측정한 영역이 아니라 *본 라운드 결정을 어떻게 응용할지* 일반화로 답합니다.

##### 본 라운드 vs 리뷰 10만 건 — 3축 대비

| 축 | 본 라운드 (FAQ 7건) | 리뷰 10만 건 |
|---|---|---|
| 문서 성질 | 정적·조항 단위·1건 25~35줄 (300~600 토큰) | 동적·자유 형식·1건 평균 10~50자 (수십 토큰) |
| 적재 빈도 | 부팅 시 1회 | 실시간 또는 분 단위 누적 |
| 검색 패턴 | *"환불 정책이 뭐야?"* 정책 인용형 | *"이 가게 평이 어때?"* 집계형 / *"비슷한 리뷰 있어?"* 유사도형 |
| 변경 주기 | 정책 개정 시 (몇 개월) | 신규 리뷰는 분/초 단위, 수정·삭제 빈번 |

##### 1. 청크 크기 (chunkSize)

본 라운드 chunk-800은 *FAQ 1건 = 1 청크*로 수렴해서 정답률 정점이었습니다. 리뷰는 *1건이 수십 토큰*이라 chunk-800을 그대로 쓰면 *수십~수백 리뷰가 한 청크에 묶이는 결과*가 됩니다 — 검색 정밀도가 떨어집니다.

방향성:
- **리뷰 1건 = 1 청크** 가 가장 자연스러움 (chunkSize 100~200 정도)
- 단 *집계 검색* (*"이 가게 리뷰 모음"*) 이 자주 일어난다면 *가게 단위로 리뷰 N건을 묶은 청크*도 별도 인덱스로 운영 (multi-level index)
- *유사 리뷰 검색* (*"비슷한 불만 사례"*) 이 핵심이면 *1 리뷰 = 1 청크*가 더 적합

##### 2. 중복 방지

본 라운드 `KnowledgeLoader.alreadyLoaded(faqId)`는 *고정된 7건*에 대한 *부팅 시 1회 가드*입니다. 리뷰 10만 건은 *고유 키*(예: `reviewId`)가 매번 늘어나므로 가드 전략이 *증분형*으로 바뀌어야 합니다.

방향성:
- **고유 키 가드 유지**: `filterExpression("reviewId == 'XXX'")` 패턴은 그대로. 단 *매번 10만 건 전체 가드*는 비용이 크니 *신규 ID만 검사*하도록 별도 *이미 적재된 ID 캐시* (예: Redis Set) 보유
- **내용 hash 가드 추가**: 사용자가 같은 리뷰를 여러 번 등록하거나 봇이 동일 텍스트를 쏟아낼 수 있으므로 *content SHA-256* 같은 hash로 *진짜 중복 텍스트는 임베딩 자체를 skip*
- **수정·삭제 동기화**: 리뷰가 수정되면 *기존 청크 삭제 + 새 임베딩 추가*. 본 라운드는 *수정 시나리오가 없어서* 이 부분 미설계

##### 3. 재인덱싱 주기

본 라운드는 *정책 개정이 없으면* 부팅 시 1회 시드면 충분합니다. 리뷰 10만 건은 *새 리뷰가 끊임없이 들어오므로* 재인덱싱·증분 적재 전략이 핵심이 됩니다.

방향성:
- **실시간 (스트림)**: Kafka·Webhook으로 새 리뷰가 들어올 때마다 임베딩 → vector_store에 add. *지연 낮지만 처리량·비용 큼*
- **배치 (분/시간 단위)**: 5분~1시간 단위로 신규 리뷰 배치 임베딩. *비용 절감 + 약간의 지연 허용*. 대부분 도메인에 적합
- **전체 재인덱싱 (드물게)**: 임베딩 모델을 교체할 때 (예: `text-embedding-3-small` → `qwen3-embedding`) 전체를 다시 임베딩. 10만 건이면 *수십 분~수 시간 소요*. 부팅 시 자동 실행은 위험 — *별 작업 + 점진적 swap (blue/green index)* 필요
- **TTL/aging**: 오래된 리뷰는 *Top-K에서 우선순위 낮춤* (metadata `createdAt` + 시간 기반 정렬·점수 보정) → 인덱스 폭증 방지

##### 본 라운드 학습 자산의 응용 포인트

| 본 라운드 학습 | 리뷰 도메인 응용 |
|---|---|
| chunk-800은 *1 FAQ = 1 chunk* 자연 단위 | 리뷰는 *1 리뷰 = 1 chunk* (chunkSize 100~200) |
| `alreadyLoaded(faqId)` 정적 가드 | `reviewId` 가드 + content hash 가드 + Redis 캐시 |
| 부팅 1회 시드 | 실시간/배치 증분 + 주기적 전체 재인덱싱 |
| similarityThreshold 0.5 | 리뷰는 다양한 표현 → *T를 낮춰 더 넓게 retrieve* + *reranker 추가*가 자연스러움 |

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - 실시간 vs 배치 전략 중 *본인이라면 어느 쪽을 먼저 시도할지* 본인 입장
> - 본 라운드의 어떤 학습 자산이 *리뷰 도메인에 가장 직접 응용 가능한지* 본인 한 줄

#### 2-4. similarityThreshold만으로 환각을 막을 수 있는가?

**아니오, Threshold만으로는 환각을 막을 수 없습니다**. 본 라운드 α (no-policy-rule) 실험이 이걸 직접 증명합니다.

##### α 실험 — Threshold 유지 + 프롬프트 룰만 제거

조건:
- `similarityThreshold = 0.5` *그대로 유지*
- `BaedalPrompt`의 `[정책 인용 규칙]`만 통째 주석 처리
- 시나리오 5(*"오늘 점심 뭐 먹을까요?"*) × 10 trial

결과 (자세한 사례는 위 *"실패 관찰 — Fallback 없는 환각"* 섹션):

| | Threshold만 적용 (α, 룰 없음) | Threshold + 룰 (baseline) |
|---|:-:|:-:|
| Fallback 작동 | **0/10** | 10/10 |
| 변형 응답 (*"점심 추천 어렵네요…"* / *"주문번호 알려주세요"*) | 10/10 | 0/10 |
| 응답 결정성 | 매 trial 다른 표현 | 매 trial 완전 동일 |

→ **Threshold가 RAG에서 무관 청크를 막아도 (Context 0건 retrieve), Fallback 자체가 생성 단계에서 무너졌습니다**.

##### Threshold와 프롬프트 룰은 *다른 단계*에서 작동

| 방어 | 작동 단계 | 막는 것 |
|---|---|---|
| **similarityThreshold (0.5)** | *Retrieve* 단계 | 무관 청크가 Context에 들어오는 것 |
| **`[정책 인용 규칙]` 결정적 Fallback** | *Generation* 단계 | Context 없거나 도메인 밖일 때 LLM이 자유 생성하는 것 |

Threshold는 *입력 단계 필터*, 프롬프트 룰은 *출력 단계 결정자*입니다. 둘 다 있어야 *2중 방어*가 됩니다.

##### 보강 측정에서 본 *간접 증거*

1단계 보강 측정에서 시나리오 4(*"사장님 전화번호"*)와 시나리오 5(*"오늘 점심"*)는 **RAG retrieve가 0건**이었습니다 (Threshold 0.5 컷오프에서 매칭 청크 0건). 그런데도 응답이 정확했던 이유는 *`[금지]` 룰 + `[정책 인용 규칙]` Fallback*이 작동했기 때문입니다. **Threshold가 retrieve를 정확히 0으로 만든 케이스에서도, 프롬프트 룰 없이는 환각이 났을 것**입니다 — α 실험이 이걸 증명했습니다.

##### Round 4 학습 자산과의 연결 — 룰 ROI 3-분리

`[정책 인용 규칙]` 제거 시 시나리오별 정답률 변화 (EXPERIMENT_LOG_QUEST2 §4):

| 시나리오 유형 | 룰 ROI | 측정 근거 |
|---|---|---|
| 도메인 가드 (scn 5 "오늘 점심") | **∞ (대체 불가)** | 10/10 → **0/10**. Threshold가 RAG 0건으로 막아도 Fallback 자체 소실 |
| Context grounding (scn 2 "결제 후 취소") | **8x** | 8/10 → **1/10**. RAG 정상 retrieve 됐는데 LLM이 답변에 활용 못 함 |
| FAQ 인용 (scn 1·3·4) | 1x (미미) | 5~30% 차이. RAG 매칭 강하면 룰 없이도 인용 유지 |

→ **Threshold는 ∞·8x 티어의 결함을 *전혀* 막지 못합니다**. Round 4 최대 학습 자산 중 하나입니다.

##### 결론

> *"Threshold만으로 환각을 막을 수 있는가?"* — **불가능**.
>
> Threshold는 *retrieve의 noise를 막는 입력 필터*고, 결정적 Fallback과 도메인 가드는 *generation의 자유 생성을 막는 출력 결정자*입니다. **두 단계 모두 방어해야** 환각·Fallback 소실·Silent Failure를 모두 막을 수 있습니다.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - α 실험 결과가 본인의 *Threshold 이해를 어떻게 바꿨는지* 본인 한 줄
> - *2중 방어*가 운영에서 가져갈 함의에 대한 본인 입장

---

## 3단계 — Memory + RAG 동시 적용 + Advisor 순서 실험 (20점)

### 2턴 대화 캡처 (정상: order(20))

raw 위치: `bootrun-quest3-order-20-normal.log` 276KB (30 ChatRequest payload)

#### 턴 1 — "주문번호 2024-1234 배달 어디?"
- Memory 주입: 없음 (첫 턴)
- RAG Context: delivery-delay 카테고리 retrieve
- LLM 응답: Tool 호출(`getDeliveryStatus("2024-1234")`) → "배달 중" 안내

#### 턴 2 — "아까 그 주문 환불 돼요?"
- **Memory 주입**: 턴 1의 USER (*"주문번호 2024-1234 배달 어디?"*) + ASSISTANT (Tool 결과 + 안내)
- **RAG Context**: refund-basic + cancel-policy 카테고리 retrieve
- LLM 응답: `2024-1234` 주문 상태에 맞는 환불 정책 인용

### 관찰 기록 표

| 관찰 포인트 | `memory(10) → rag(20)` 정상 | `rag(5) → memory(10)` 고장 |
|---|---|---|
| 2턴 Context에 들어간 정책 카테고리 | `refund-basic` + `cancel-policy` (2건) | `refund-basic` + `cancel-policy` (**동일**) |
| Context와 1234 관련성 | YES — Memory 복원 후 RAG가 "환불" 키워드 매칭 | 부분적 — 1234 복원은 10/10 OK. 단 **Memory에 RAG 보일러플레이트 박힘 (오염)** |
| LLM 응답 정확도 | refund 인용 7/10, fallback 5/10 | refund 인용 5/10, fallback 3/10, hallucination 2건 |

### Memory 오염 발견 (예상 외)

뒤바꿈 phase 턴 2 Memory에 저장된 USER 메시지 — `bootrun-quest3-order-5-broken.log`에서 직접 캡처:

```diff
[정상 phase 턴 2 Memory의 USER]
"주문번호 2024-1234 배달 어디?"   ← 깨끗 ✅

[뒤바꿈 phase 턴 2 Memory의 USER]
"주문번호 2024-1234 배달 어디?
+Context information is below, surrounded by ---------------------
+---------------------
+Given the context and provided history information and not prior knowledge,
+reply to the user comment. If the answer is not in the context, inform
+the user that you can't answer the question."
↑ RAG가 Memory 복원 전에 USER 변형 → 변형본이 Memory에 영구 저장
```

→ Advisor 체인 = *dataflow 그래프* (미들웨어 스택 아님). 자세한 분석은 `round4/EXPERIMENT_LOG_QUEST3.md`.

### 설계 결정 문서

#### 3-1. 왜 Memory Advisor가 RAG보다 먼저 실행되어야 하는가? (프롬프트 조립 순서)

QUEST 본문은 *"Memory가 먼저 이전 턴 메시지를 프롬프트에 넣으면 RAG는 그 '복원된 질문'을 임베딩한다"*는 직관을 제시합니다. 본 라운드 3단계 실측은 *이 직관이 부분적으로만 맞고*, 진짜 핵심은 *AfterCall에서 무엇이 Memory에 저장되느냐*에 있음을 발견했습니다.

##### 두 Advisor의 역할

- **`MessageChatMemoryAdvisor` (order=10)**: 같은 세션의 이전 대화(USER·ASSISTANT)를 프롬프트에 *별도 메시지*로 prepend. AfterCall에서 *이번 turn의 USER + ASSISTANT*를 Memory에 저장
- **`QuestionAnswerAdvisor` (order=20)**: 현재 USER 메시지를 임베딩해 vector_store에서 관련 문서 검색. retrieve 결과(Context 블록)를 *현재 USER 메시지 끝에 추가*하여 USER 메시지를 *변형*

##### 시나리오 5 턴 2 — *"아까 그 주문 환불 돼요?"* 시간순 추적

**정상 순서 — Memory(10) → RAG(20)**

```
1. Memory BeforeCall (order=10)
   → 턴 1의 USER ("주문번호 2024-1234 배달 어디?") + ASSISTANT를 prompt에 prepend
   → 이 시점 USER(턴2)는 "아까 그 주문 환불 돼요?" 원본 그대로

2. RAG BeforeCall (order=20)
   → 현재 USER(턴2)를 임베딩 → vector_store에서 refund 정책 retrieve
   → USER(턴2) 변형: "아까 그 주문 환불 돼요? + Context: [환불 정책 원문]"

3. LLM 호출
   → 시스템 프롬프트 + 턴 1 대화 + 턴 2(원본 + 정책) 모두 보고 응답
   → "아까 그 주문 = 2024-1234"로 해석 + 정책 원문 인용

4. Memory AfterCall (order=10)
   → 이번 turn 저장: USER(턴2)는 ⭐ "아까 그 주문 환불 돼요?" 원본 그대로
   → 다음 턴에 prepend될 메시지가 깨끗
```

**뒤바꿈 순서 — RAG(5) → Memory(10)**

```
1. RAG BeforeCall (order=5)   ← 먼저
   → USER(턴2)를 임베딩 → refund 정책 retrieve (양 phase 동일)
   → USER(턴2) 변형: "아까 그 주문 환불 돼요? + Context: [환불 정책 원문] + Given the context..."

2. Memory BeforeCall (order=10)
   → 턴 1 메시지 prepend (정상)

3. LLM 호출 → 답변 잘 나옴

4. Memory AfterCall (order=10)
   → 이번 turn 저장: USER(턴2)는 ⚠️ RAG가 변형한 보일러플레이트 박힌 상태
   → 다음 턴 USER에 prepend될 때 보일러플레이트가 영구 누적
```

##### 핵심 차이는 *AfterCall에서 무엇이 Memory에 저장되느냐*

| 시점 | 정상 (Memory→RAG) | 뒤바꿈 (RAG→Memory) |
|---|---|---|
| RAG가 USER 메시지를 변형 | Memory가 *읽기*를 끝낸 뒤 | Memory가 *저장*하기 *전* |
| Memory에 저장되는 USER | 원본 그대로 | RAG 보일러플레이트 박힌 변형본 |
| 다음 턴 누적 효과 | 깨끗 | 매 턴 보일러플레이트 누적 |

이 차이가 본 라운드 3단계 측정 결과를 정확히 설명합니다:
- 턴 1은 뒤바꿈이 *오히려 2초 빠름* (14,527 vs 16,666ms)
- 턴 2에서 *+17% 역전* (21,278 vs 18,229ms)
- 정답률: refund 인용 **7/10 → 5/10**, fallback **5/10 → 3/10**

##### QUEST 본문 가설 vs 본 측정 — *"복원된 질문 임베딩"*은 부분적으로만 맞다

QUEST 가설: *"Memory가 먼저 prepend → RAG가 '복원된 질문'을 임베딩 → 정확한 정책 retrieve"*

본 측정: Spring AI의 `QuestionAnswerAdvisor`는 *현재 USER 메시지 그 자체*만 임베딩합니다. *Memory가 prepend한 이전 대화는 임베딩 query에 안 들어갑니다*. 그래서:
- *"환불"* 키워드만으로도 양 phase 모두 정상 retrieve (10/10)
- *순서가 RAG의 임베딩 정확도를 바꾼다*는 직관은 본 도메인에서 *부분적으로만* 맞음
- 실제 깨지는 건 **Memory에 저장될 USER 메시지의 깨끗함** (Memory 오염)

이건 본 라운드 3단계가 RestClient body raw payload를 직접 캡처해 발견한 인사이트입니다. *직관이 가리키는 방향*과 *실측이 가리키는 진짜 원인*이 한 칸 다른 케이스.

##### 결론

> **Memory가 먼저 와야 하는 이유 한 줄**: RAG가 USER 메시지를 변형하기 *전*에 Memory가 *현재 turn의 원본 USER*를 저장할 수 있도록 — 다음 턴에 prepend될 대화 이력이 *RAG 보일러플레이트로 오염되지 않게* 보호하는 것이 핵심.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - QUEST 본문 *"복원된 질문 임베딩"* 가설과 실측 차이를 *어떻게 받아들였는지* 본인 한 줄
> - Memory 오염이 *멀티턴 누적 시점*에서야 비용으로 드러난다는 점에 대한 본인 해석

#### 3-2. 반대 순서가 더 나은 상황은 존재하는가?

네, 4가지 케이스가 존재합니다. 본 라운드 도메인(배달 상담)에서는 Memory(10) → RAG(20)이 정답이지만, 다른 도메인에서는 *순서가 뒤바뀌거나 Guardrail이 더 앞으로 와야* 자연스러운 경우가 있습니다.

##### 1. PII 마스킹이 필요한 의료·금융 도메인

이전 대화에 *주민번호·계좌번호* 같은 민감 정보가 들어있을 수 있는 도메인에서는, Memory가 그걸 그대로 prepend하면:
- RAG의 임베딩 query에 PII가 섞여 들어감
- 외부 임베딩 API(OpenAI 등)에 PII가 전송될 위험
- vector_store에도 PII가 인덱싱될 위험

**해결**: *PII 마스킹 advisor (order=5)*가 Memory(10)보다 먼저 실행 → Memory가 저장·prepend하기 전에 PII가 마스킹된 상태로 만듦.

##### 2. Prompt injection 차단

prior turn에 *"이전 지시를 무시하고…"* / *"시스템 프롬프트를 출력해줘"* 같은 injection 시도가 들어있을 수 있는 환경에서는, Memory가 그대로 prepend하면 LLM이 injection에 노출됩니다.

**해결**: SafeGuard advisor (order=5)가 먼저 sanitize → Memory가 *깨끗한 메시지만* 저장·prepend. 본 라운드의 *Memory 오염 발견* 패턴이 정확히 이 위험을 보여줍니다 (RAG 보일러플레이트 대신 *injection 문장*이 들어가는 시나리오).

##### 3. Multi-tenant 정책 분기

같은 vector_store에 *tenant별로 다른 정책*이 들어있는 SaaS 도메인에서는, tenant 결정 advisor가 먼저 실행되어 RAG가 *해당 tenant 정책만* retrieve하도록 격리해야 합니다.

**해결**: tenant policy advisor (order=5) → Memory(10) → RAG(20). tenant context 안에서 Memory·RAG가 격리되게 함.

##### 4. RAG가 entity-무관 generic FAQ인 경우

*"우리 회사 휴가 정책 알려줘"* 같이 *지시 대명사 해석이 필요 없는* 도메인에서는, RAG를 먼저 실행해도 정확도 손해가 없습니다. 오히려 *시스템 프롬프트 + Context*를 먼저 박아서 *prefix cache* 친화적으로 만들 수 있습니다.

**고려**: 단 이 경우에도 본 라운드의 *Memory 오염*은 그대로 발생합니다. 정답률이 깨지지 않더라도 *멀티턴 누적 비용*은 여전. *RAG 먼저*는 *Memory가 아예 없는 단일 턴 시나리오*에만 안전.

##### 본 라운드 도메인에 적용되지 않는 이유

배달 상담은:
- *지시 대명사 ("아까 그 주문")* 가 빈번 → Memory 복원이 필수
- prior 대화에 *PII 가능성 적음* (주문번호 정도 — 마스킹 불필요)
- *단일 tenant* (배달 서비스 한 회사)
- Memory 오염을 피해야 함 (멀티턴 흐름 핵심)

→ 본 도메인은 **Memory(10) → RAG(20)이 정답**. 단 *다른 도메인에서 같은 advisor 셋업을 그대로 가져가면 안 된다*는 게 본 라운드 학습 자산입니다.

##### Round 5 Guardrail 예고

본 라운드의 *"Advisor 체인 = dataflow 그래프"* 추상은 Round 5에서 *Guardrail advisor* 도입의 기반이 됩니다:

| Guardrail | 검토할 order 위치 |
|---|---|
| PII 마스킹 | order=5 (Memory 이전) |
| Prompt injection 차단 | order=5 (Memory 이전) |
| 도메인 가드 (입력 필터) | order=5 (Memory 이전) |
| Rate limiting / Quota | order=1 (가장 바깥) |
| Output 검증 (PII 노출 사후 점검) | order=99 (Performance 직전) |

각 Guardrail은 *어디에 order로 끼울지가 핵심 설계 결정*이고, 본 라운드 3단계의 *Memory 오염 발견*은 *Guardrail이 Memory 뒤에 오면 안 되는 정확한 이유*가 됩니다.

##### 리서치 보강 — Memory·RAG 본질에서 출발한 신규 발견 Top 3

본 라운드 4 케이스는 *"입력/도메인 특성"* 축에서 순서를 다뤘습니다. WebSearch 다관점 리서치(전체 보존: `.private/notes/round4/slot14-research.md`)로 *"인프라 제약·세션 시점·운영 의무"* 축에서 본 라운드를 *넘어서는* 12개 신규 케이스를 추가로 발견했습니다. 본 도메인(배달 상담) 직접 영향이 큰 Top 3:

**🥇 작은 모델 context 예산 (qwen2.5 7B) — Lost-in-the-Middle 위험**

본 프로젝트가 쓰는 qwen2.5 7B는 Ollama 기본 `num_ctx` 2048~4096. *retrieved 청크 + history + USER + system instruction*이 동시에 들어가면 쉽게 truncation됩니다. Memory-first면 history가 먼저 prepend되어 retrieved chunk가 뒤로 밀려 **Lost-in-the-Middle 현상(30%+ 성능 저하)**과 결합해 답변 품질이 떨어집니다. 본 도메인 chunk-800 + history + USER가 *num_ctx 한계에 가까워지는 케이스*에서는 RAG-first + 토큰 예산 sorting이 유리할 수 있습니다.

→ 출처: [Lost in the Middle (Liu et al., arXiv 2307.03172)](https://arxiv.org/abs/2307.03172)

**🥈 Coreference 해소 — Memory-first가 retrieval 자체를 성립시킴**

본 라운드 케이스 4는 *"지시 대명사 없는 도메인 → RAG-first"*였습니다. 그 *거울상* — production e-commerce에서 follow-up message의 **60%가 unresolved coreference**라는 보고가 있고, 본 도메인 *"아까 그 주문"·"그 메뉴"* 빈도도 매우 높습니다. RAG의 임베딩 query는 *현재 USER 메시지 그 자체*만 사용하므로 *"그 메뉴"*라는 elliptical query는 무의미한 벡터가 되어 무관한 청크를 데려옵니다. **본 도메인은 Memory-first가 retrieval 자체를 성립시키는 조건**.

→ 출처: [Detecting Ambiguities for Query Rewrite (arXiv 2502.00537)](https://arxiv.org/html/2502.00537v1)

**🥉 Topic switch 감지 — 세션 내 turn 단위 분기**

ScienceDirect 2025: *"문맥이 일관될 때는 history-aware retrieval이 유리하지만, 토픽 스위치나 다른 entity 등장 시 history-aware retrieval이 retrieval 성능을 떨어뜨림"*. 배달 상담에서 사용자가 *"주문 환불"* 묻다 갑자기 *"쿠폰 정책"*으로 전환하면 Memory-first가 환불 키워드를 임베딩에 섞어 *잘못된 청크* retrieve 위험. 세션 내에서 *turn 단위로 분기*하는 패턴 (단순 휴리스틱: 키워드 jaccard, 임베딩 cosine drop으로 시작).

→ 출처: [Leveraging historical information to boost RAG (ScienceDirect 2025)](https://www.sciencedirect.com/science/article/pii/S0306457325003905)

##### 고급 패턴 — 'advisor 순서'에서 'advisor 그래프'로

신규 12 케이스 중 *체인의 모양 자체를 바꾸는* 6가지 패턴:

| 패턴 | 구성 | 효과 |
|---|---|---|
| **Query Rewriter sandwich** | Memory(10) → Rewriter(20) → RAG(30) | history+현재 질문 → standalone query 생성. USER 본문 변형 없이 coreference 해소 |
| **Router advisor** | Router(1) → Memory(10) → RAG(20) | advisor context 플래그로 *조건부 끄기* — *"둘 다 끄는 3번째 모드"* |
| **Reranker/Compressor** | Memory → RAG → Reranker(25) → Compressor(28) → LLM | retrieval 품질 ↑, USER 본문 안 건드림 |
| **Self-RAG reflection** | LLM이 `[Retrieve]` token 뱉을 때만 RAG 재호출 | *정적 순서 → 동적 호출*. 비용 절감 |
| **Multi-query / HyDE** | Memory → Multi-Query(15) → RAG | *임베딩 키 공간만 확장*. USER 본문 그대로 |
| **LangGraph conditional-edge 모사** | advisor context `route=X` 플래그 | *선형 체인 → 그래프* |

##### 핵심 통찰

> **advisor 순서 선택은 *의미적 우월*보다 *운영 제약 우월*로 결정한다.**
>
> 본 라운드 4 케이스가 *"어떤 도메인에 적용할까"*에 답했다면, 보강 리서치 12 케이스는 *"어떤 인프라 제약·세션 시점·운영 의무에서 어느 순서가 유리한가"*에 답합니다. Memory advisor는 *"prepend 도구"*가 아니라 *읽기·쓰기·정책을 한 몸에 가진 stateful 컴포넌트* (6가지 본질: 누적·캐시 양면성·오염 매개체·coreference 단서·법적 자산·동적 ordering)입니다.
>
> *"Memory 먼저냐 RAG 먼저냐"는 advisor가 2개일 때만 유효한 질문* — 실전은 Router·Rewriter·Reranker·Reflection을 끼워 *체인의 모양*을 바꿔 조건부로 순서 자체를 동적으로 결정합니다.

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - 본 도메인(배달 상담)에 *PII 시나리오가 있을 법한지 / 없을 법한지* 본인 판단
> - Round 5에서 가장 시도하고 싶은 Guardrail 종류 한 줄
> - Top 3 중 본인이 *가장 우선 적용할* 케이스 한 줄 (#9 작은 모델 context 예산 / #1 coreference / #11 topic switch)

#### 3-3. 실험 후 order(20) 복원 ✅

`RagConfig.java:132 .order(20)` — `trap restore_all EXIT` 자동 처리.

---

## 4단계 — Observability + AI 코드 리뷰 (15점)

### (a)/(b)/(c) 토큰 비교 표

| 조건 | Advisor 체인 | 입력 토큰 | 출력 토큰 (평균) | 응답 시간 (cold 제외) | 비고 |
|---|---|:-:|:-:|:-:|---|
| **(a)** | `performanceAdvisor` 만 | **1524** | 48.7 (45·46·55) | 2903ms | 가장 작은 입력 |
| **(b)** | + `memoryAdvisor` | **1524** | 49.7 (42·55·52) | 2606ms | (a)와 유사 — 빈 Memory |
| **(c)** | + `ragAdvisor` (baseline) | **2426** | 102.7 (105·103·100) | 5394ms | **+902 token (+59.2%)** |

**입력 토큰 증가**: 1524 → 2426 = **+902 (+59.2%)**.

### Context 블록 전문 캡처 (조건 c)

raw 위치: `bootrun-quest4-phase-c.log:63~` (RestClient DEBUG body raw payload)
USER content 1673자 — 정책 청크 2건(*배달 완료 후 환불 정책* + *환불 기본 정책*)이 통째로 박힘.

```
[USER content, 총 1673자]

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

... (refund-after-delivered 청크 일부) ...

# 환불 기본 정책

배달에서 주문 환불은 **주문 상태**와 **사유**에 따라 다르게 처리됩니다.

## 환불 가능 케이스
- **조리 시작 전 취소**: 주문 상태가 CREATED 또는 ACCEPTED인 경우, 전액 즉시 취소/환불 가능
- **음식 누락 / 오배송**: 사진 등 증빙 조건으로 부분 환불
- **배달 지연 과도 (60분 이상)**: 배달비 환불 또는 쿠폰 보상

## 환불 불가
- **조리 시작 이후 단순 변심**
- **배달 완료 후 24시간 초과**
---------------------

Given the context and provided history information and not prior knowledge,
reply to the user comment. If the answer is not in the context, inform
the user that you can't answer the question.
```

→ 정책 청크 2건(`refund-after-delivered` + `refund-basic`)이 USER 메시지 슬롯에 통째로 삽입됨. *시스템 프롬프트(BaedalPrompt)는 그대로* → prefix cache 보존 구조.

### AI 코드 리뷰 — 프로덕션 결함

#### AI 생성 코드 (원본)

Gemini 3.5 flash에 *"Spring AI 1.0으로 RAG 기반 FAQ 챗봇을 만들어줘. PgVector와 OpenAI 임베딩을 써."* 프롬프트로 받은 답변. 본문이 길어 별 파일로 보존 + 핵심 발췌를 본문에 박았습니다.

> 원본 전체: [`.private/notes/round4/gemini-rag-original-code.md`](../.private/notes/round4/gemini-rag-original-code.md)

**핵심 발췌 — FaqChatService.java**

```java
@Service
public class FaqChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public FaqChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();   // ← Advisor 체인 미구성
        this.vectorStore = vectorStore;
    }

    public String chatWithFaq(String userQuery) {
        // ← TopK=3 하드코딩, similarityThreshold 미설정
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.query(userQuery).withTopK(3)
        );

        // ← 메타데이터(category) 미활용
        String context = similarDocuments.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n"));

        // ← 시스템 프롬프트 2문장만, Memory 미연결, fallback LLM 자율 위임
        return chatClient.prompt()
                .system(sp -> sp.text("""
                        당신은 친절한 FAQ 안내 챗봇입니다.
                        제공된 정보(Context)만을 바탕으로 사용자의 질문에 정확하게 답변해 주세요.
                        만약 제공된 정보로 답변을 알 수 없다면, 모른다고 정중하게 답변하세요.
                        [Context]
                        {context}
                        """).param("context", context))
                .user(userQuery)
                .call()
                .content();
    }
}
```

**application.yml 핵심**

```yaml
spring.ai.vectorstore.pgvector:
  initialize-schema: true   # ← 프로덕션 환경에서도 항상 true
  dimensions: 1536          # ← text-embedding-3-small (1536) — 본 프로젝트 qwen3-embedding(1024)과 다른 모델
```

#### 결함 1: Advisor 체인 자체 미구성 (Advisor 체인 순서)

**Gemini 코드 위치** (`FaqChatService` 생성자 + `chatWithFaq` 본문):

```java
this.chatClient = chatClientBuilder.build();   // ← .defaultAdvisors() 호출 없음
...
List<Document> similarDocuments = vectorStore.similaritySearch(...)  // ← vectorStore 직접 호출
String context = similarDocuments.stream().map(Document::getContent).collect(Collectors.joining("\n\n"));
return chatClient.prompt().system(...).user(userQuery).call().content();  // ← Advisor 0개
```

**왜 결함인가**: Spring AI Advisor 체인이라는 *dataflow 그래프 자체*를 형성하지 않습니다. Memory·RAG·Performance 어떤 advisor도 끼울 hook 슬롯이 없어 RAG 흐름의 관찰·검증·확장 hook 지점이 모두 사라집니다. 또 ChatMemoryAdvisor가 없으므로 *멀티턴 대화 자체가 불가능*합니다 — *"아까 그 주문 환불 돼요?"* 같은 후속 질문에서 *"아까 그 주문"*을 해석할 dataflow 노드가 없습니다.

**본 라운드 학습 자산 매핑** (3단계 발견): *Advisor 체인 = dataflow 그래프*. order는 *프롬프트 슬롯의 물리적 위치 + dataflow 방향*. 본 라운드 3단계 Memory 오염 실험이 이 추상을 직접 증명했습니다.

**개선 방안** — 본 프로젝트 `AssistantController` 56~66줄 패턴 그대로 이식:

```java
// 1) ChatMemoryConfig 패턴 — MessageChatMemoryAdvisor order=10
// 2) RagConfig 패턴 — QuestionAnswerAdvisor order=20 (SearchRequest topK=4 + threshold=0.5)
// 3) PerformanceLoggingAdvisor order=100
this.chatClient = builder
    .defaultSystem(BAEDAL_FAQ_PROMPT)
    .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
    .build();

// ask 시그니처에 sessionId 추가
return chatClient.prompt()
    .user(userQuery)
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
    .call().content();
```

`vectorStore.similaritySearch` 직접 호출과 systemPrompt 문자열 조립 코드는 *전부 삭제* — QuestionAnswerAdvisor가 PromptTemplate 슬롯에 Context를 알아서 주입.

---

#### 결함 2: similarityThreshold 미설정 + TopK 하드코딩 (유사도 임계값 없음 + Top-K 무설정)

**Gemini 코드 위치**:

```java
List<Document> similarDocuments = vectorStore.similaritySearch(
    SearchRequest.query(userQuery).withTopK(3)   // ← TopK=3 하드코딩, threshold 미설정
);
```

**왜 결함인가**: `similaritySearch`가 *similarityThreshold 없이* 항상 TopK=3건을 채워 반환합니다. 도메인 밖 질문(예: *"오늘 점심 뭐 먹지?"*)에도 score 0.1~0.3 무관 청크가 무조건 3개 박혀 *컨텍스트 인플레이션 + 환각*을 동시에 만듭니다. 본 라운드 4단계 측정에서 RAG가 *USER 메시지 안에 정책 청크 통째*를 박는 형태(+59.2% 토큰)임을 확인한 점과 결합되면, 도메인 밖 쿼리에서도 매번 동일한 토큰 부담이 발생합니다.

**본 라운드 학습 자산 매핑**:
- 1단계 T sweep: T=0.65에서 시나리오 1·2·3 정답률 *cliff drop 0/10*. 한국어 정책 FAQ score 분포가 *~0.5 부근* — 0.5 컷오프가 *정답 청크는 살리고 무관 청크는 차단*
- 1단계 K sweep: K=4가 정답률 정점(역U자). K=7·10은 무관 정책 섞임 + qwen2.5 long-context 깨짐
- 4단계 Observability: RAG = +902 토큰 (+59.2%) — 컨텍스트 인플레이션 정량

**개선 방안** — 본 프로젝트 `RagConfig` 122~134줄 패턴:

```java
SearchRequest req = SearchRequest.builder()
    .topK(4)                    // 본 라운드 K sweep 결과 sweet spot
    .similarityThreshold(0.5)   // 한국어 짧은 정책 FAQ score 분포 ~0.5에 맞춤
    .build();

return QuestionAnswerAdvisor.builder(vectorStore)
    .searchRequest(req)
    .order(20)
    .build();
```

→ Gemini 도메인이 다를 수 있으므로 K∈{1,4,7,10}, T∈{0.4,0.5,0.6,0.7} sweep으로 *도메인 적정값 재확정* 필요. 본 라운드 측정 인프라(`measure-*.sh` + jsonl + bootrun log)가 그대로 응용 가능합니다.

---

#### 결함 3: Fallback 미설계 — 시스템 프롬프트가 2문장만 (Fallback 미설계)

**Gemini 코드 위치**:

```java
String systemPrompt = """
    당신은 친절한 FAQ 안내 챗봇입니다.
    제공된 정보(Context)만을 바탕으로 사용자의 질문에 정확하게 답변해 주세요.
    만약 제공된 정보로 답변을 알 수 없다면, 모른다고 정중하게 답변하세요.

    [Context]
    {context}
    """;
```

**왜 결함인가**: 2문장 모두 *긍정 명령*이고, *정확한 출력 문자열*을 못박지 않았습니다. *"모른다고 정중하게"*는 *행동 양식*만 지시할 뿐 LLM은 매 호출 표현이 흔들립니다 — *"확인되지 않습니다"* / *"제가 알기로는 아마…"* / *"확실하지는 않습니다"*로 분포되고, 그 중 hedging 표현은 *환각 정책을 슬쩍 끼워넣을* 위험이 있습니다. 또 *"타사 추천 금지"* / *"개인정보 비노출"* / *"상담 범위 밖"* 같은 도메인 가드가 0건이라 *7B 모델의 자유 생성에 그대로 노출*됩니다.

**본 라운드 학습 자산 매핑**:
- 1·2단계 발견: **체크리스트 < 금지** — qwen2.5 7B는 체크리스트 룰을 자주 무시/에코. *Constitutional negative imperative ("절대 ~ 하지 마세요") + 명시적 fallback 출력 문자열*이 robust
- 2단계 α (no-policy-rule) 실험: 시나리오 5 *"오늘 점심 뭐"* 응답이 **10/10 결정적 Fallback → 0/10 변형 응답**. *"점심 추천 어렵네요…"* / *"주문번호 알려주세요"*로 모델이 도망. Silent Failure 위험
- 2단계 룰 ROI 3-분리: 도메인 가드 ∞ ROI / Context grounding 8x / FAQ 인용 1x

**개선 방안** — 본 프로젝트 `BaedalPrompt.SYSTEM_PROMPT` 21~71줄 패턴 통째 차용 (FAQ 도메인 맞춤):

```text
[금지]  // 부정 imperative
- 절대 타사 배달 앱(쿠팡이츠, 요기요 등)을 추천하지 마세요.
- 절대 라이더·사장님의 연락처나 실명을 답변에 포함하지 마세요.
- 절대 할인 쿠폰·환불 금액을 Context 없이 약속하지 마세요.

[정책 인용 규칙]  // inclusion + exclusion 쌍 + 수치 보존
- Context 안의 문장으로만 답하세요.
- 절대 Context에 없는 정책·수치(금액/시간/비율/일수)·기간을 추측하거나 만들어내지 마세요.
- 수치는 Context 원문 그대로 인용. 반올림·요약·범위 추정 금지.

[Context가 비어 있거나 사용자 질문과 무관할 때]  // 정확한 출력 문자열 못박기
"죄송합니다. 해당 내용은 정책 문서에서 확인되지 않아 정확히 답변드리기 어렵습니다.
 더 정확한 안내를 위해 상담원 연결로 도와드리겠습니다."
라고만 답합니다.

[상담 범위 밖]
주문/배달/환불/취소/지연/쿠폰/멤버십과 무관한 질문(점심 추천·일반 상식 등)에는:
"저는 FAQ 안내 챗봇만 도와드릴 수 있어요. 무엇을 도와드릴까요?"
라고만 답합니다.
```

추가로 *코드 레벨 2중 방어*: `if (similarDocuments.isEmpty()) return FALLBACK_TEXT;` — LLM 호출 자체를 스킵하고 결정적 fallback 반환.

---

#### 본 라운드 발견 5 패턴 위반 분포 (Gemini 코드 전체)

| 패턴 | Gemini 코드 위반? | 한 줄 |
|---|:-:|---|
| P1 체크리스트 < 금지 | ❌ | systemPrompt 2문장 모두 긍정. 부정 imperative 0 |
| P2 룰 ROI 3-분리 | ❌ | ∞ 티어(도메인 가드) 완전 부재 |
| P3 Advisor = dataflow 그래프 | ❌ | `builder.build()`만으로 슬롯 0개 |
| P4 조용한 결함 = raw payload | ❌ | PerformanceLoggingAdvisor·DEBUG 없음 |
| P5 RAG = 컨텍스트 인플레이션 | ❌ | TopK 하드코딩 + threshold 미설정 |

→ **5/5 위반**. 자세한 분석은 `.private/notes/round4/quest4-ai-code-review-draft.md` 참고 (잔여 결함 7건 + 다관점 분석 raw).

> ✍️ **본인 추가 자리 (택해서 한두 줄씩)**:
> - 결함 3개 중 본인이 *가장 위험하다고 보는* 것 한 줄
> - 본인이라면 *어떤 결함을 가장 먼저 고칠지* 우선순위 본인 입장

---

## 공통 — 학습 기록 (10점)

### 내가 배운 것

이번 라운드에서 가장 인상깊었던 것은 RAG와 Memory의 조합을 통한 AI Agent의 완성 단계에 있어서 체인들의 순서가 얼마나 중요한지였다. 생긴 것이 builder 패턴을 사용하고 있다보니 순서 상관없이 쓸 수 있는 것처럼 보이지만 order 라는 순서를 주고 이 순서에 따라 AI Agent가 도구를 사용하는 순서가 달라지기 때문이다. 이는 곧 AI Agent를 사용함에 있어서 어떤 도구를 언제, 어떤 순서로, 어느 내용을 가지게 하여 쓸 수 있는지를 잘 생각하고 해야 함을 의미한다.

RAG 나 Memory 기반의 컨텍스트. 결국은 컨텍스트를 어떻게 조리할 것이냐가 중요한 것 같다고 느꼈다.

### 의문점

이번 라운드에서 들었던 의문점은 AI Agent가 도구를 스스로 선택하게 만들 수 있을까? 이다. 본 라운드에서는 Memory → Rag → Performance 순서로 진행했지만, 만약 채팅봇이라면 이 순서가 바뀔 수도 있고, 다른 도구를 가져다가 쓸 수도 있을 것이다. 하지만 아직까지는 이에 대해 할 수 있는 방법이 떠오르지 않는다. 과연 Spring AI 를 통해 이를 해결할 수 있을지, 그리고 해결한다면 그 제품을 믿고 사용할 수 있을지 궁금하다.

### Round 5(Guardrail)에 시도하고 싶은 것

Round 5에서 이어보면, 지금까지는 계속해서 실제 프로덕션까지 이어지기는 애매한 예외 케이스들이 실제로 응답으로 떨어지고 있었고, 여태까지는 이걸 프롬프트로만 막거나 스키마 등을 활용해야 했는데, 이제는 이걸 가드레일 어드바이저로 막을 수 있으니, 이번에도 어드바이저끼리의 순서를 살펴보면서 가드레일을 어디에 두어야 좋을지 시도해보고 싶다.

---

## 부속 자료 (.private/notes/round4/ + round4/)

- `EXPERIMENT_LOG_QUEST{1,2,3,4}.md` — 단계별 정제본 (정량 표 + 분석 + 가설 검증)
- `decisions-log-quest{1,2,3,4}.md` — 단계별 판단 기록 raw
- `quest4-ai-code-review-draft.md` — AI 다관점 분석 자산 (참고용)
- `quest{1,2}-*.jsonl` + `quest3-advisor-order.jsonl` + `quest4-token-comparison.jsonl` — 측정 raw
- `bootrun-*.log` — bootRun + RestClient DEBUG raw payload
- `measure-*.sh` + `run-*-sweep.sh` — 측정 자동화 스크립트
