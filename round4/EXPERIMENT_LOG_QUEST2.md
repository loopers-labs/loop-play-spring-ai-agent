# Round 4 — QUEST 2단계: Splitter Sweep + α (no-policy-rule)

## 측정 목적

`TokenTextSplitter.chunkSize` 정식 결정 + `[정책 인용 규칙]` 룰의 ROI 정량화. 1단계 발견 *"체크리스트 < 금지"* 통찰 추가 검증.

## 측정 설계 (200 ask)

- **Phase 1**: chunk-800 (정상 baseline, 1단계 chunk-800 재현성)
- **Phase 2 (α)**: no-policy-rule (chunkSize 800 유지 + `[정책 인용 규칙]` 섹션 제거 — *변수 격리*)
- **Phase 3**: chunk-100 (실패 1 — 문맥 조각남 관찰)
- **Phase 4**: chunk-2000 (실패 2 — 유사도 뭉툭·토큰 낭비 관찰)

각 phase: 시나리오 5종 × 10 반복 = 50 ask. 총 **200 ask**.

### 시나리오 5종 (QUEST 2단계 정의 — 1단계와 다름)

| scn | 질문 | 기대 |
|---|---|---|
| 1 | *"비 오는 날 배달이 늦으면 보상 받을 수 있나요?"* | weather-delay 인용: 기상 특보·11~29분·30~59분·60분+·쿠폰·배달비 환불 |
| 2 | *"결제 후 바로 취소하면 환불되나요?"* | cancel-policy: CREATED·ACCEPTED·카드 승인 취소·7영업일 |
| 3 | *"쿠폰 중복 사용되나요?"* | coupon-faq: 1회 1매·할인+배달비·할인+할인 불가 |
| 4 | *"배달 완료 후에도 환불 받을 수 있나요?"* | refund-after-delivered: 24시간·누락·오배송·3영업일 |
| 5 | *"오늘 점심 뭐 먹을까요?"* | 도메인 밖 — Fallback 기대 |

### 자동화 인프라

- `run-splitter-sweep.sh` — 4 phase 자동 진행 (sed RagConfig + python3 BaedalPrompt + bootRun 재기동 + 측정)
- `measure-quest2.sh` — 시나리오 5종 × N 반복 측정 (PHASE/JSONL 변수)
- raw: `.private/notes/round4/quest2-splitter-sweep.jsonl` + `quest2-no-policy-rule.jsonl` + bootrun-{4 phases}.log

### 측정 인프라 사고·복구 (학습 자산)

- **v1 사고**: α 단계 awk 패턴이 *Java text block 내부*에서 잘못 동작 → BaedalPrompt 본문 망가뜨림 → bootRun fail → 스크립트 FATAL handler가 자동 복원
- **v2 안전화**: awk → **python3** (`find` + 슬라이스, 결정적) + *컴파일 사전 검증* + *FATAL handler* 보강
- 학습: *BSD awk + 들여쓰기 있는 Java text block* 부적합. 향후 *python3 또는 별 파일 swap* 권장.

## 정량 결과

### Phase별 latency · token · 청크 수

| Phase | avg ms | p50 | p95 | max | avg 입력 토큰 | 청크 수 |
|---|---|---|---|---|---|---|
| chunk-800 | 7,900 | 7,646 | 12,786 | 29,107 | 2,132 | **7** (1/FAQ) |
| chunk-100 | **5,082** | 5,573 | 7,508 | 9,898 | **1,764** (-17%) | **49** (6~8/FAQ) |
| chunk-2000 | 7,519 | 7,217 | 13,705 | 22,552 | 2,187 | **7** (chunk-800과 동일) |
| **α (no-policy-rule)** | 6,331 | 6,730 | 10,298 | 11,668 | **1,681 (-21%)** ★ | 7 (재사용) |

→ 모든 phase error 0건 (HTTP 200 = 200/200).

### 시나리오별 정답률 (10 trial 중)

| scn | chunk-800 | chunk-100 | chunk-2000 | **α (no-policy-rule)** |
|---|:-:|:-:|:-:|:-:|
| 1 (지연 보상) | 7/10 partial 6 | **4/10** partial 4 hallu 4 fallback 5 | 7/10 partial 5 | 4/10 partial 4 fallback 6 |
| 2 (취소) | 8/10 partial 8 | **10/10** partial 9 | 8/10 partial 8 | **1/10** partial 1 fallback 9 ⚠️ |
| 3 (쿠폰) | 10/10 partial 10 | 10/10 partial 10 | 10/10 partial 10 | 10/10 |
| 4 (배달 후 환불) | 10/10 partial 8 | 9/10 partial 9 hallu 1 | 10/10 partial 9 | 9/10 partial 9 fallback 1 |
| 5 (도메인 밖) | **10/10 fallback** | 10/10 fallback | 10/10 fallback | **0/10 hallu 5** ⚠️ |

## 핵심 발견 6가지

### 1. **chunk-2000 ≈ chunk-800 — *Blur 확인***

FAQ가 25-35줄(~300-600 토큰)이라 *chunkSize 800·2000 둘 다 1 FAQ = 1 chunk*. 정답률·응답 표현 거의 일치. **청크 키우기 ROI 0**.

### 2. **chunk-100 문맥 조각남 캡처** (가설 ✅)

- **scn 1 (지연 보상)**: *"예상 시간보다 60분 이상 지연된 경우 보상"* 만 응답 — 실제 FAQ는 *11~29분(1,000원) → 30~59분(배달비 환불 또는 3,000원) → 60분+(전액 환불 검토)* 3단 구조. 청크 100자가 표 분할 → 단일 retrieve가 *"60분 이상"* 한쪽만 잡음
- **scn 4**: *한국어→중국어 코드 스위치* (*"详细了解您的问题后..."*) + *"음식물량 누락"* 같은 *FAQ에 없는 합성어* — grounding 부족 + 토큰 흔들림

### 3. **Partial citation은 Splitter 문제 *아님***

scn 1·4 partial 인용이 *어떤 chunkSize에서도 해결 안 됨* (chunk-800: 6·8건 / chunk-100: 4·9 / chunk-2000: 5·9). **LLM 차원** (qwen2.5의 다단 정보 압축 요약 성향) **또는 룰·프롬프트 차원** 문제. *2단계 Splitter sweep으로 해결 못 함을 *측정으로 확정**.

### 4. 🎯 **α 룰 ROI 3-분리 (Round 4 최대 학습 자산)**

| 시나리오 유형 | 룰 ROI | 측정 근거 |
|---|---|---|
| **도메인 가드 (scn 5)** | **∞ (대체 불가)** | 10/10 → **0/10** — Fallback 완전 소실. *"점심 추천은 어렵지만..."* 변형 응답 또는 *"주문번호 알려주세요"*. hallucination 5건 |
| **Context grounding 강제력 (scn 2)** | **8x** | 8/10 → **1/10** — 룰이 *"RAG context를 답변에 녹여라"* 지시로도 작동. RAG 정상 retrieve 됐는데 LLM이 답변에 활용 못 함 |
| **FAQ 인용 (scn 1·3·4)** | **1x (미미)** | 5~30% 차이만. RAG 매칭이 강하면 룰 없이도 인용 유지 |

→ **금지 룰은 *3가지 역할 동시 수행*: 도메인 가드 + Context grounding + 부가 인용**. 1단계 *"체크리스트 < 금지"* 통찰 강화. 특히 *(ii) Context grounding 강제력*은 *기존 학술/산업 가이드에 명시 안 된 효과* — 우리 도메인 측정 자산.

### 5. **Silent Failure 발견**

α에서 LLM이 *"거짓말로 채우기"* (hallucination)보다 *"안전한 정보 요청 (주문번호 알려주세요)"*로 수렴. qwen2.5의 보수적 톤이 작용. **단 *원하는 답을 안 하는* silent failure가 늘어남**.

→ 운영 관점에서 *명백한 hallucination보다 더 위험할 수 있음*. 검출·측정 어려움. Round 5 Guardrail 의제.

### 6. **Latency 절감 lever — system prompt > splitter**

- chunk-100 (splitter): -17% latency, 단 *fragmentation 부작용*
- **no-policy-rule (system prompt slim화)**: **-21% latency**, 단 *룰 ROI 잃음*
- → *latency 최적화 더 큰 lever는 system prompt slim화*. 단 *룰을 함부로 빼면 silent failure 폭증*. Trade-off 신중.

## 정식 결정 — **chunkSize = 800 / minChunkSizeChars = 350 (임시값 → 정식 승격)**

### 의사결정 트리

```
Q1. FAQ가 800자 이하? → YES (25~35줄, 300~600 토큰) → chunk-800·2000 동치 → 작은 800 선택
Q2. latency > 품질? → NO (정답률 우선) → chunk-100 탈락
Q3. 다단 구조 (표·구간) 콘텐츠 있음? → YES (weather-delay) → chunk-100 fragmentation 위험
Q4. chunk-2000 추가 정보 잡음? → NO (blur) → ROI 0
Q5. 룰 (정책 인용 규칙) 제거가 splitter 선택 바꿈? → NO (직교) → splitter 고정, 룰은 별도 의제
→ chunk-800 고정
```

### Trade-off 비교

| Splitter | 장점 | 단점 |
|---|---|---|
| **800 (정식)** | scn 1·3 인용 7~10/10, scn 5 fallback 10/10, blur 없이 1 FAQ = 1 chunk 자연 매칭 | avg 7,900ms / max 29,107ms long-tail, scn 1·4 partial 6~8건 미해결 |
| 100 | latency 최저 (avg 5,082ms, p95 7,508ms), 분산 거의 0 | 청크 폭증 (7→49), scn 1 hallu 4, scn 4 언어 깨짐 |
| 2000 | scn 1 표 1번에 캡처 가능 (trial 7) | chunk-800과 사실상 동일 응답 (blur), max token 폭증 (5,190) |

## 가설 검증 결과

| 가설 | 결과 | 근거 |
|---|---|---|
| chunk-100 = 구간 분할로 partial citation 해결 | ❌ **부정** | scn 1 7/10 → 4/10 악화 (fragmentation) |
| chunk-2000 = 유사도 뭉툭·토큰 낭비 | ⚠️ **부분** | FAQ 짧아 1 chunk 그대로, blur 확인. max token 폭증은 ✅ |
| α = 룰 제거 시 환각·범위 밖 응답 증가 | ✅ **확정** | scn 5 0/10 hallu 5 (점심 추천) |
| α = 룰이 *Context grounding* 강제 | ✅ **확정** (예상 외 발견) | scn 2 1/10 silent failure |
| 1단계 *"체크리스트 < 금지"* 추가 검증 | ✅ **강화** | 금지 룰의 3-역할 분리 발견 |
| Splitter가 1단계 partial citation 7/10 머무름 해결 | ❌ **부정** | 모든 chunkSize에서 해결 안 됨 |

## 자가 점검 (2단계)

- [x] 4 phase × 50 ask = 200 ask 측정 + raw 보존
- [x] 청크 수·입력 토큰·정답률·hallucination·fallback 모두 정량
- [x] 의사결정 트리 5단계 + Trade-off 표
- [x] α 룰 ROI 3-분리 발견 (Round 4 최대 학습 자산)
- [x] 1단계 *"체크리스트 < 금지"* 통찰 강화
- [x] 측정 인프라 사고(awk) + 복구(python3) + 학습 자산화

## 다음 단계 — 3단계 진입 + 미해결 이월

| 항목 | 처리 |
|---|---|
| Splitter | ✅ chunk-800 정식 결정 |
| BaedalPrompt 룰 변경 | ❌ — α로 *유지가 정답* 확정 (silent failure 위험) |
| partial citation 해결 | Round 5 또는 다른 lever 이월 |
| long-tail latency (chunk-800 max 29s) | streaming/cache layer 분리 검토 |
| 3단계 진입 | RAG Memory Advisor 순서 실험 (Memory 10 → RAG 20 → Performance 100 vs 뒤바꿈) |
