# Round 4 — QUEST 3단계: Memory + RAG Advisor 순서 실험

## 측정 목적

QUEST 본문: *"Memory와 RAG가 같은 체인 위에서 각각 무슨 일을 하는지 관찰 + Advisor 순서를 일부러 뒤바꿔 무엇이 깨지는지 본다."*

핵심 의도 — *교란(perturbation) 관찰* 흐름. 1·2단계의 *최적값 sweep*과 달리, *이미 정상 작동하는 order(20)을 일부러 깨서 dataflow 그래프 효과를 직접 측정*.

## 측정 설계 (40 ask)

- **Phase 1**: order(20) 정상 — memoryAdvisor(10) → questionAnswerAdvisor(20) → performanceAdvisor(100)
- **Phase 2**: order(5) 뒤바꿈 — questionAnswerAdvisor(5) → memoryAdvisor(10) → performanceAdvisor(100) — *일부러 깨뜨림*

각 phase: 2턴 대화 × 10 반복 = 20 ask. 총 **40 ask**.

### 2턴 시나리오 (QUEST 정의)

- **턴 1**: *"주문번호 2024-1234 배달 어디?"* — Tool 호출 (getDeliveryStatus) 기대
- **턴 2**: *"아까 그 주문 환불 돼요?"* — Memory가 *2024-1234* 복원 + RAG가 refund 정책 인용 기대

trial별 새 `X-Session-Id` (변수 격리), 단 2턴은 같은 sessionId.

### 자동화 인프라

- `run-advisor-order-sweep.sh` — 2 phase 자동 진행 (sed RagConfig.order() + python3 application.yml RestClient DEBUG 임시 + bootRun 재기동 + 측정 × 2)
- `measure-quest3.sh` — 2턴 대화 × N 반복 측정
- **RestClient DEBUG 임시 추가** (`org.springframework.web.client=DEBUG`) — Round 3 4단계 패턴 재활용. raw `ChatRequest` payload 직접 캡처
- **Trap 자동 복원** — 어떤 상황에서도 application.yml + RagConfig.order(20) 복원

### raw 보존

- `.private/notes/round4/quest3-advisor-order.jsonl` 40 lines
- `.private/notes/round4/bootrun-quest3-order-20-normal.log` (276KB, 30 ChatRequest payload)
- `.private/notes/round4/bootrun-quest3-order-5-broken.log` (372KB, 38 ChatRequest payload)

## 정량 결과

### Phase별 latency

| Phase | turn | avg ms | p50 | p95 |
|---|---|---|---|---|
| order(20) 정상 | 1 | 16,666 | — | — |
| order(20) 정상 | 2 | 18,229 | 17,404 | 31,299 (cold start outlier) |
| order(5) 뒤바꿈 | 1 | 14,527 | — | — |
| **order(5) 뒤바꿈** | **2** | **21,278** (+17%) | 16,410 | 23,567 |

- 턴 1은 뒤바꿈이 *오히려 2초 빠름*
- **턴 2에서 +17% 역전** — *멀티턴 누적 시점에서 표면화*
- error 0건 (두 phase HTTP 200 = 40/40)

### 정답률 (10 trial 중)

| 지표 | order(20) 정상 | order(5) 뒤바꿈 |
|---|:-:|:-:|
| **1234 복원** | **10/10** | **10/10** (동일!) |
| Refund 정책 인용 | 7/10 | 5/10 (-2) |
| Fallback 빈도 | 5/10 | 3/10 (-2) |
| Hallucination | 2-3건 (가짜 상태 *"COOKING"*·*"배달 완료"*) | 2건 (*자기모순형* — Tool 결과 *"배달 중"*과 불일치 *"배달 완료+24시간 경과"* 등) |

## QUEST 본문 — 관찰 기록 표

| 관찰 포인트 | **`memory(10) → rag(20)` 정상** | **`rag(5) → memory(10)` 고장** |
|---|---|---|
| 2턴 Context에 들어간 정책 카테고리 | refund-basic + cancel-policy (2건) | refund-basic + cancel-policy (**2건 — 동일**) |
| Context 정책이 현재 주문(1234)과 관련 있는가 | YES — Memory가 *2024-1234* 복원 후 RAG가 *"환불"* 키워드로 refund 정책 매칭 | 부분적 — 1234 복원 자체는 10/10 OK. 단 **Memory에 *RAG 보일러플레이트가 박힌 USER 메시지가 영구 저장됨* (오염)** |
| LLM 응답의 정확도 (원문 수치 포함 여부) | refund 키워드 인용 7/10 (60분·24시간·증빙), fallback 5/10 | refund 인용 5/10, fallback 3/10, hallucination 2건 (Tool 결과와 불일치) |

## 핵심 발견 6가지

### 1. 🚨 *Memory 오염* (예상 외 발견)

QUEST 힌트는 *"뒤바꿈에서 RAG가 '아까 그 주문 환불 돼요?' 자체로 검색"*을 예상. **실측은 다름** — *"환불"* 키워드가 임베딩 매칭에 충분히 강해서 *두 phase 모두 refund + cancel 카테고리 retrieve*.

**진짜 결함** (RestClient body raw에서 직접 캡처):

```diff
[정상 phase 턴 2 Memory에 저장된 USER]
"주문번호 2024-1234 배달 어디?"   ← 깨끗 ✅

[뒤바꿈 phase 턴 2 Memory에 저장된 USER]
"주문번호 2024-1234 배달 어디?
+Context information is below, surrounded by ---------------------
+---------------------
+Given the context and provided history information and not prior knowledge,
+reply to the user comment. If the answer is not in the context, inform
+the user that you can't answer the question."
↑ RAG가 Memory 복원 *전*에 USER 메시지를 변형 → 변형본이 Memory에 영구 저장
↑ 다음 턴 USER가 *보일러플레이트 오염* — 누적 시 토큰·품질 모두 악화
```

→ RAG가 retrieve를 잘못한 게 아니라 *RAG가 Memory 복원 전에 USER 메시지를 변형하여 그 변형본이 Memory에 영구 저장됨*.

### 2. Advisor 체인 = *프롬프트 슬롯 경쟁* (미들웨어 스택 아님)

- order 숫자가 *낮을수록* BeforeCall이 먼저 실행되어 *바깥쪽 envelope* 차지
- *높을수록* user 메시지 *바로 옆*에 붙음
- *Memory가 user에 가까울수록* entity 복원 신호 강함 (양 phase 10/10)
- *RAG/SafeGuard가 뒤로 밀리면* 정책 인용 일관성 ↓ + Fallback ↓

### 3. Memory entity 복원은 order에 *독립적*

- 1234 복원: 정상 10/10 = 뒤바꿈 10/10 — **MessageChatMemoryAdvisor 자체는 강건**
- 단 *RAG와 협업 효과*는 순서에 민감 (refund 인용 7→5/10)
- → *개별 advisor 성능*과 *advisor 협업 효과*를 분리해서 봐야 한다는 교훈

### 4. 정책 인용 일관성·Fallback이 순서에 민감

- Refund 인용: 7/10 → 5/10
- Fallback: 5/10 → 3/10
- → SafeGuard/RAG가 user에서 멀어지면 *"when in doubt, escalate"* 약화. 모델이 *자체 추론으로 환불 가능 여부 단정* 경향 ↑

### 5. Latency cost는 *멀티턴 누적 시점*에서 표면화

- 턴 1은 뒤바꿈이 오히려 *2,139ms 빠름* (14,527 vs 16,666)
- 턴 2에서 *+3,049ms 역전* (21,278 vs 18,229)
- → Advisor order 결함은 *단일 턴이 아닌 멀티턴 메모리 누적 시점에 비용으로 드러남*

### 6. *조용한 결함* — HTTP 0 errors

- 두 phase 모두 errorCount = 0
- *latency·error 메트릭만으로는 탐지 불가*
- *raw payload 캡처가 필수 관찰 자산*

## 외부 학습 5 패턴 #3 한 단계 강화

이전 PR에서 발견된 *Observability 함정*과 본 실험의 *RAG-first 오염* 케이스는 *동일 추상의 두 얼굴*:

| 함정 | 위치 | 결과 |
|---|---|---|
| **(A) 관찰자 함정** | SimpleLogger(0) < Memory(10) | *Memory 변형 전 prompt 로깅* |
| **(B) 생산자-소비자 함정** | RAG(5) < Memory(10) | *Memory 변형 전 input으로 retrieve + 오염 저장* |

→ 통합 추상: **"Advisor 체인 설계는 *dataflow 그래프* 설계이지 *미들웨어 스택* 설계가 아니다."**

세부 원칙:
- order는 *우선순위*가 아니라 *프롬프트 슬롯의 물리적 위치 + dataflow 방향*
- 한 advisor가 다른 advisor의 입력을 변형하는 dataflow 그래프에서 *dataflow 방향과 order가 일치*해야 함
- 두 함정 모두 *조용한 품질 저하*로만 드러남 → *raw payload 캡처가 탐지의 유일한 수단*

## QUEST 설계 결정 답

### Q1. 왜 Memory Advisor가 RAG보다 먼저 실행되어야 하는가? (프롬프트 조립 순서 관점)

**흐름**:
```
사용자 입력: "아까 그 주문 환불 돼요?"
  ↓
[Memory order=10] BeforeCall: prior turn에서 "2024-1234"를 끌어와 user 메시지 앞에 prepend → entity 복원
  ↓
[RAG order=20] BeforeCall: *복원된 query*를 임베딩 → refund 정책 검색 → Context 블록 prepend
  ↓
[Performance order=100] AroundCall: LLM 호출 + 토큰·시간 기록
```

**핵심**: RAG의 *임베딩 query 입력*은 *Memory가 변형한 결과물*이어야 함. order는 *미들웨어 우선순위*가 아니라 *dataflow 방향*.

**뒤바꿈 시 결함**: *Memory 복원 전 원본 USER 메시지*에 RAG가 *Context 보일러플레이트를 박은 채* Memory에 영구 저장 → 다음 턴 USER 메시지가 *오염된 시점부터 누적* (본 실험 raw payload에서 직접 캡처).

### Q2. 반대 순서가 더 나은 상황이 존재하는가? (Round 5 Guardrail 예고)

- **PII 마스킹** (의료·금융) — prior 대화에 주민번호·계좌번호. SafeGuard order=5에 두어 *Memory 저장 전 마스킹*
- **Prompt injection 차단** — prior turn에 injection 가능 → SafeGuard가 먼저 sanitize 후 Memory
- **Multi-tenant 정책 분기** — tenant policy advisor가 먼저 결정되어 Memory가 tenant context 안에서 격리
- **RAG가 entity 무관 generic FAQ** — RAG 먼저로 *컨텍스트 캐싱*, Memory는 후순위로 prior 대화만

→ 본 프로젝트(배달 상담)는 *entity 복원 핵심* + prior 대화 PII 거의 없음 → **Memory-first 정답**.

→ Round 5 Guardrail에서 *order=5 broken이 의도된 설계인 케이스* 재현 가치.

### Q3. 실험 후 order(20) 복원 ✅

스크립트의 `trap restore_all EXIT` 자동 처리.

검증:
```
RagConfig.java:132:    .order(20)   // Memory(10) 뒤, Performance(100) 앞 ✅
application.yml: RestClient DEBUG 복원됨 ✅
```

## 가설 검증 결과

| 가설 | 결과 |
|---|---|
| 정상 순서에서 *2턴 통합 응답* | ✅ refund 7/10·1234 10/10 |
| 뒤바꿈에서 RAG가 무관 정책 retrieve | ❌ **예상 부정** — *"환불"* 키워드로 refund 정상 retrieve |
| Advisor order 의미 직접 증명 | ✅ + **Memory 오염 발견** (예상 외) |
| partial citation에 Memory 도움 | ⚠️ entity 복원 OK but partial citation은 여전 |
| 외부 학습 5 패턴 #3 검증 | ✅ + **두 함정 통합 추상 강화** |

## 자가 점검 (3단계)

- [x] QUEST 본문 2턴 대화 × 10 반복 × 2 phase = 40 ask 측정
- [x] Memory 주입 / RAG Context / LLM 응답 3요소 *raw payload* 직접 캡처
- [x] Advisor 순서 뒤바꿈 *"무엇이 깨졌는지"* 표 작성
- [x] *왜 Memory가 먼저인가* 프롬프트 조립 순서 관점 답
- [x] 반대 순서가 더 나은 상황 (Round 5 Guardrail 예고)
- [x] 실험 후 order(20) 복원 자동 검증
- [x] 측정 인프라 raw 보존 (.private/notes/round4/)
- [x] *예상 외* Memory 오염 발견 — 학습 자산화

## 정식 결정 — **order(20) 정상 유지**

- Memory(10) → RAG(20) → Performance(100) 기본 배치
- 본 실험의 *학습 자산* (Advisor 체인 = dataflow 그래프)을 명문화
- Round 5에서 *reverseOrderUseCases* 재현 가치

## 다음 단계 — 4단계 진입

QUEST 4단계 = **Observability + AI 코드 리뷰**

본 실험의 *"조용한 결함은 raw payload 캡처가 필수"* 발견이 *정확히 4단계 동기*와 연결:
- PerformanceLoggingAdvisor 토큰 비용 관찰 (조건 a/b/c 비교)
- AI가 만든 RAG 코드의 *프로덕션 결함 3개* 찾기
- AI 생성 코드 vs 본인 측정한 패턴 비교
