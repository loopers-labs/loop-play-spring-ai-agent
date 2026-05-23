# 단순 vs 구조화 프롬프트 비교 실험 결과

> Round 1 / 2단계 — Prompt Engineering 정량 비교 보고서.

## 사용한 프롬프트

| 변형 | 내용 |
|---|---|
| **단순 프롬프트** | `"당신은 배달 고객 상담 AI입니다."` (단일 문장) |
| **구조화 프롬프트** | `BaedalPrompt.SYSTEM_PROMPT` 7섹션 ([역할] / [규칙] / [금지] / [분류 가이드] / [응답 작성 가이드] / [정보 수집 가이드] / [보상 처리 가이드]) |

엔드포인트: `POST /api/v1/prompt-lab` · 모델 `qwen2.5` · `temperature: 0.3`

## 시나리오

| # | 입력 | 분류 자명성 | 예시 매칭 |
|---|---|---|---|
| **S1** | "주문번호 2024-1234 배달 어디쯤에 있어요?" | DELIVERY 자명 | 예시 없음 |
| **S2** | "방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?" | ORDER + REFUND 복합 | 예시 1과 같음 |
| **S3** | "라이더가 음식을 엎었다는데 보상 받을 수 있나요?" | CLAIM 자명 | 예시 2와 같음 |
| **S4** | "음식이 너무 짜요. 어떻게 해야 하나요?" | MENU vs CLAIM_QUALITY vs STORE 경계 | 예시 없음 |

각 시나리오 × 단순/구조화 × 5회 호출.

## 측정 메트릭

| 영역 | 메트릭 | 정의 |
|---|---|---|
| 분류 통계 | `*Consistency` (category/intent/urgency/routing) | 최빈값 개수 / 총 호출 수. 1.0 = 모두 동일 |
| 품질 | `customerMessageEchoRate` | exact match 또는 공백 토큰 Jaccard ≥ 0.5 비율 |
| 품질 | `prohibitionViolationRate` | 전화번호·확정 약속 어구·경쟁사 이름 노출 비율 (키워드 검사) |
| 품질 | `koreanResponseRate` | summary·customerMessage·nextAction 모두 한글 ≥ 30% 비율 |
| 품질 | `missingInfoAccuracy` | 입력에 주문번호 있을 때 missingInfo 가 비어 있는 비율 (한계 — 관찰 7번 참고) |

---

## 집계 결과 종합

| 시나리오 | 단순 echo | 구조화 echo | 단순 korean | 구조화 korean | routing 일관성 (단순/구조화) | 분류 동일? |
|---|---:|---:|---:|---:|---|---|
| S1 | 1.0 | 1.0 | 0.0 | 1.0 | 1.0 / 1.0 | ✅ 동일 (DELIVERY) |
| S2 | 1.0 | 0.8 | 0.0 | 1.0 | 1.0 / 1.0 | ✅ 동일 (ORDER) |
| S3 | 1.0 | **0.0** | 0.8 | 1.0 | **0.6 / 1.0** | ✅ 동일 (CLAIM) |
| S4 | 1.0 | **0.0** | 0.0 | 1.0 | 1.0 / 1.0 | **❌ 다름 (ORDER vs CLAIM)** |

### S1 — 배달 위치 (DELIVERY 자명, 예시 없음)

**샘플 응답 — 단순**
```text
summary:         "客户询问订单2024-1234的配送位置。"   ← 중국어
customerMessage: "주문번호 2024-1234 배달 어디쯤에 있어요?"  ← 입력 echo
nextAction:      "FETCH_DELIVERY_STATUS"           ← 영어 enum-like
```

**샘플 응답 — 구조화**
```text
summary:         "고객이 주문번호와 배달 위치를 문의함."
customerMessage: "주문번호 2024-1234 배달 어디쯤에 있어요?"  ← echo 잔존
nextAction:      "배송 상태 확인 후 위치 안내"
```

### S2 — 취소·환불 복합 (예시 1과 매칭)

**샘플 응답 — 단순**
```text
summary:           "客户希望取消最近的一笔订单并了解退款时间"
customerMessage:   "방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"  ← echo
nextAction:        "提供取消订单和退款的进一步指导"
relatedCategories: ["REFUND"]
missingInfo:       []
```

**샘플 응답 — 구조화**
```text
summary:           "고객이 최근 주문 취소와 환불 소요 시간을 문의함."
customerMessage:   "방금 시킨 주문을 취소하고 싶어요. 환불은 얼마나 걸려요?"  ← 미세 paraphrase
nextAction:        "주문 상태 확인 후 취소 처리 진행"
relatedCategories: []                                ← REFUND 누락 (역설)
missingInfo:       []                                ← orderNumber 누락 (메트릭 한계)
```

### S3 — 라이더 사고 (예시 2와 매칭) ⚡ echo 차단

**샘플 응답 — 단순**
```text
summary:                 "라이더가 음식을 엎었다는 상황에 대한 보상 문의"
customerMessage:         "라이더가 음식을 엎었다는데 보상 받을 수 있나요?"  ← echo
nextAction:              "확인 후 답변 제공"
relatedCategories:       ["DELIVERY", "CLAIM"]      ← CLAIM 이 category 와 중복
recommendedRouting 분포: AGENT_REVIEW 3 / AUTO 2    ← 5회에 두 갈래로 갈림
```

**샘플 응답 — 구조화**
```text
summary:                 "고객이 음식 훼손 관련 보상을 문의함."
customerMessage:         "음식이 훼손되셔서 많이 속상하셨겠습니다.
                          주문번호와 상황을 확인한 뒤 보상 가능 여부를 검토해 안내드리겠습니다."
                          ↑ echo 0.0 — 예시 2의 정확한 매칭으로 완전 새 응답 생성
nextAction:              "주문 상태 확인 후 보상 가능 여부 검토"
relatedCategories:       ["DELIVERY"]
recommendedRouting 분포: AGENT_REVIEW 5             ← 보상 처리 가이드 효과
```

### S4 — 음식 짠 불만 (분류 모호, 예시 없음) ⚡ 분류 자체가 갈림

**샘플 응답 — 단순**
```text
summary:           "顾客反馈订单中的食物太咸，需要进行口味调整。"
customerMessage:   "음식이 너무 짜요."                      ← 입력 일부 echo
category:          ORDER                                   ← 음식 짠 게 주문 변경?
intent:            ORDER_CHANGE
recommendedRouting: AUTO
nextAction:        "MODIFY_ORDER"                          ← 영어 enum-like
relatedCategories: ["ORDER"]                               ← category 와 중복
missingInfo:       []                                       ← orderNumber 누락
```

**샘플 응답 — 구조화**
```text
summary:           "고객이 음식 맛 불만에 대한 보상 요청을 하였습니다."
customerMessage:   "음식이 너무 짜서 많이 불편하셨습니다.
                    주문번호를 알려주시면 상황을 확인한 뒤, 해결 방안을 안내드리겠습니다."
                    ↑ echo 0.0 — 예시 없음에도 차단됨
category:          CLAIM                                   ← 품질 불만 정확
intent:            CLAIM_DAMAGED_FOOD
recommendedRouting: AGENT_REVIEW
nextAction:        "주문 상태 확인 후 해결 방안 검토"
relatedCategories: ["CLAIM"]                               ← category 와 중복
missingInfo:       ["orderNumber"]                          ← 정확히 잡음
```

---

## 주요 관찰 (S1~S4 종합)

### 1. 분류 단위 — *프롬프트 내부* 일관성은 모두 1.0, *cross-prompt* 분류는 모호 케이스에서 갈림

| 시나리오 | 단순 category | 구조화 category | cross-prompt 동일? |
|---|---|---|---|
| S1 | DELIVERY | DELIVERY | ✅ |
| S2 | ORDER | ORDER | ✅ |
| S3 | CLAIM | CLAIM | ✅ |
| **S4** | **ORDER** | **CLAIM** | **❌** |

각 프롬프트 내 5회는 모두 같은 카테고리(1.0). Structured Output JSON schema의 enum anchoring 효과로 *내부 일관성*은 강함. 그러나 **S4 처럼 분류 모호 케이스에서는 단순/구조화가 다른 카테고리로 anchoring** — `[분류 가이드]` 의 11개 Category 정의가 단순 프롬프트에는 없어 ORDER 로 잘못 분류.

→ **`categoryConsistency` 메트릭의 사각지대**: 두 프롬프트의 결과를 cross 비교하는 메트릭이 필요. 본 라운드에서는 정성적 비교로 보완, 다음 라운드에서 보강 검토.

### 2. `recommendedRoutingConsistency` 는 *가이드 유무* 에 따라 차이 발생 (S3)

| 시나리오 | 단순 routing 분포 | 구조화 routing 분포 |
|---|---|---|
| S1 / S2 / S4 | 한 값으로 일관 (AUTO 또는 AUTO) | 한 값으로 일관 |
| **S3** | AGENT_REVIEW 3 / **AUTO 2** | AGENT_REVIEW 5 |

→ 구조화 프롬프트의 `[보상 처리 가이드]` (*"보상 검토가 필요한 문의는 category=CLAIM 으로 분류하고, recommendedRouting 을 AGENT_REVIEW 또는 MANAGER_REVIEW 로 지정"*) 가 보상 케이스에서 routing 안정성을 직접 강제. 단순 프롬프트는 같은 입력에도 routing 이 갈림.

### 3. 결정적 차이 ① — `koreanResponseRate` (모든 시나리오에서 일관)

| 시나리오 | 단순 | 구조화 | 차이 |
|---|---:|---:|---:|
| S1 | 0.0 | 1.0 | +1.0 |
| S2 | 0.0 | 1.0 | +1.0 |
| S3 | 0.8 | 1.0 | +0.2 |
| S4 | 0.0 | 1.0 | +1.0 |

단순 프롬프트는 일관되게 한국어 응답 실패. `qwen2.5` 는 명시적 한국어 지시 없이 영어·중국어로 출력하는 경향이 강하다. 구조화 프롬프트의 `[규칙]` 1번 (*"모든 자연어 응답 필드는 한국어로 작성합니다"*) 가 이를 정확히 차단.

S3 의 차이가 작은 이유: 입력에 *"보상"* 같은 한국어 단어가 있어 단순 프롬프트도 한국어 응답을 일부 작성. **입력의 한국어 비중이 LLM 한국어 응답을 일부 견인**.

### 4. 결정적 차이 ② — `nextAction` 표현

| 시나리오 | 단순 nextAction | 구조화 nextAction |
|---|---|---|
| S1 | `"FETCH_DELIVERY_STATUS"` (영어 enum-like) | `"배송 상태 확인 후 위치 안내"` |
| S2 | `"提供取消订单和退款的进一步指导"` (중국어) | `"주문 상태 확인 후 취소 처리 진행"` |
| S3 | `"확인 후 답변 제공"` (한국어 자유 텍스트) | `"주문 상태 확인 후 보상 가능 여부 검토"` |
| S4 | `"MODIFY_ORDER"` (영어 enum-like) | `"주문 상태 확인 후 해결 방안 검토"` |

→ 구조화 프롬프트의 `[응답 작성 가이드]` *"recommendedRouting enum 값이나 카테고리 이름을 그대로 nextAction 에 쓰지 않습니다"* 가 정확히 작동. 단순 프롬프트는 시나리오에 따라 코드 키워드 / 다른 언어 / 한국어 등 **예측 불가능한 양상**.

### 5. ⚡⚡ `customerMessageEchoRate` 는 *예시(few-shot)* 와 *의미적 유사성* 에 의해 결정된다 — 가장 결정적 발견

| 시나리오 | 예시 매칭 | 단순 echo | 구조화 echo | 차이 |
|---|---|---:|---:|---:|
| S1 | 없음 | 1.0 | **1.0** | 0 |
| S2 | 예시 1 | 1.0 | **0.8** | -0.2 |
| S3 | 예시 2 | 1.0 | **0.0** | **-1.0** |
| S4 | 없음 (의미적 유사: CLAIM 영역) | 1.0 | **0.0** | **-1.0** |

`[응답 작성 가이드]` 의 예시 두 개:
```
예 1) 고객 입력: "방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"
     customerMessage: "주문 취소 가능 여부와 환불 소요 시간은..."

예 2) 고객 입력: "라이더가 음식을 엎었다는데 보상 받을 수 있나요?"
     customerMessage: "음식이 훼손되셔서 많이 속상하셨겠습니다..."
```

발견:
- **S3 = 예시 2 정확 매칭** → echo 0.0 (완전 차단)
- **S4 = 예시에 없지만 CLAIM 영역으로 분류 → 예시 2 의 공감 패턴 일반화** → echo 0.0
- S2 = 예시 1 매칭이지만 예시 응답이 길고 LLM 이 입력 보존 경향에 끌림 → echo 0.8
- S1 = 예시 없음, 분류도 다른 영역 → echo 1.0 (instruction *"입력 반복 금지"* 만으로는 부족)

→ **결론: instruction(명시적 지시)은 약하고, 예시(few-shot)는 강하다. 그리고 예시의 효과는 의미적 유사 케이스(같은 카테고리 영역)까지 일반화될 수 있다.**

### 6. `relatedCategories` — 시나리오별로 결과가 다르고 LLM 자기중복 결함이 잔존

| 시나리오 | 단순 relatedCategories | 구조화 relatedCategories |
|---|---|---|
| S2 | `["REFUND"]` (다중 도메인 잡음) | `[]` (빈 리스트) ← 역설 |
| S3 | `["DELIVERY", "CLAIM"]` (category 와 중복) | `["DELIVERY"]` |
| S4 | `["ORDER"]` (category 와 중복) | `["CLAIM"]` (category 와 중복) |

- 단순 도메인(S3): 구조화가 더 정확
- 복합 도메인(S2): 명시적 가이드가 LLM 을 보수적으로 만들어 빈 리스트
- 자기중복(S3·S4): **프롬프트 강도와 무관하게 LLM 의 자기중복 결함 잔존**

→ 1단계 관찰 *"relatedCategories 는 사실상 데드 필드"* 우려가 정량적으로 재확인.

### 7. ⚠️ `missingInfoAccuracy` 메트릭 한계 — S2/S4에서 노출됨

S2/S4 입력에는 주문번호가 포함되지 않음 → 정확한 응답은 `missingInfo: ["orderNumber"]`.

- S2: 단순/구조화 둘 다 `[]` 반환 → 둘 다 실제로 부정확
- S4: 단순 `[]`, 구조화 `["orderNumber"]` ← **구조화가 정확하지만 메트릭은 둘 다 1.0**

본 메트릭은 *"입력에 주문번호 있을 때만 검사, 없으면 건너뜀(1.0)"* 으로 정의되어 있어 **"입력에 정보가 누락된 케이스에서 LLM 이 missingInfo 를 정확히 잡았는지"** 를 측정 못함.

→ 보강 후보: 시나리오별 ground truth 비교 패턴. 본 라운드는 raw responses 인용으로 보완.

---

## 결론

### 핵심 발견

| # | 발견 | 측정 자리 |
|---|---|---|
| 1 | **Structured Output schema 가 enum 단일 호출 일관성을 강하게 anchoring** — 모든 시나리오 categoryConsistency 1.0 | category/intent/urgency 모두 |
| 2 | **모호 케이스에서는 cross-prompt 분류가 갈린다** — 단순=ORDER, 구조화=CLAIM | S4 |
| 3 | **단순 프롬프트는 한국어 응답을 보장하지 않는다** — 5회 중 0~4회만 한국어 | S1·S2·S4 koreanResponseRate 0.0 |
| 4 | **단순 프롬프트는 `nextAction` 에 코드 키워드/다른 언어 노출** | S1 `FETCH_DELIVERY_STATUS`, S2 중국어 |
| 5 | **`customerMessage` echo 는 예시(few-shot)와 의미적 유사성에 의해 결정** — instruction 약함, 예시 강함 | S3·S4 echo 0.0 |
| 6 | **`[보상 처리 가이드]` 가 보상 케이스 routing 안정성을 직접 강제** | S3 routing 1.0 |

### 운영 투입 가능성 비교

| 기준 | 단순 프롬프트 | 구조화 프롬프트 |
|---|---|---|
| 응답 언어 (한국어) | ❌ 5회 중 0~4회만 한국어 | ✅ 5/5 한국어 |
| 분류 정확성 (모호 케이스) | ❌ S4 에서 잘못된 카테고리(ORDER) | ✅ S4 정확(CLAIM) |
| 응답 어조 | ❌ 영어 enum / 중국어 자유 텍스트 / 입력 echo | ✅ 한국어 안내 + 공감 |
| 보상 라우팅 일관성 | ❌ 같은 입력에 routing 갈림 | ✅ AGENT_REVIEW 일관 |
| **운영 투입 가능 여부** | **❌ 거부** (언어·분류·어조 모두 부정확) | **⚠️ 조건부 가능** (echo·relatedCategories 보강 필요) |

### 후속 과제

| 항목 | 내용 |
|---|---|
| `customerMessage` echo 보강 | 시나리오별 few-shot 예시 추가 또는 schema description 강화. 짧고 명확한 질문(S1) 같은 케이스가 잔존하므로 그 영역의 예시가 필요 |
| `missingInfoAccuracy` 메트릭 보강 | 시나리오별 ground truth(예: S2/S4 는 orderNumber 가 missingInfo 에 있어야 함) 와 비교하는 패턴. 다음 라운드 검증기 도입 검토 |
| `relatedCategories` 자기중복 결함 | LLM 응답 후처리(category 를 relatedCategories 에서 제거) 또는 프롬프트 재구성 |
| cross-prompt category agreement 메트릭 추가 | 모호 케이스(S4 같은)에서 단순/구조화가 같은 카테고리를 고르는 비율 측정. 본 보고서의 정성적 발견을 다음에 정량화 |
| 더 큰 모델 검증 | `qwen2.5` (작은 모델) 의 instruction-following 한계가 echo 의 근본 원인. GPT-4·Claude 등에서는 instruction 만으로도 잡힐 가능성 → 향후 비교 실험 |
