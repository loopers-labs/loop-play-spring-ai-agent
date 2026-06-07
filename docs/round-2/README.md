# Round 2 — Tool Calling

> Round 1의 *프롬프트로만 통제* 한계(meta-routing 실패, 인젝션 우회, AI 리뷰가 짚은 보안 결함 1·2·3)를 받아 — 이번 라운드는 **Tool로 외부화**. *판단=LLM / 실행=서버* 경계를 코드로 긋고, `@Tool` 자동 호출 흐름을 검증한다.

> Round 1 본문: [../round-1/README.md](../round-1/README.md)

## Round 2 진행 상황

| 단계 | 핵심 산출물 | 상태 |
|---|---|---|
| [1단계](#1단계-tool-3개-구현--mock-데이터-확장) | Tool 3개(`getOrderDetail` / `getDeliveryStatus` / `cancelOrder`) + Mock 6건 + `assistantChatClient` 빈 분리 + 5종 시나리오 실측(**Tool 선택 실패 2건 · hallucination 1건 · 일본어 가나 드리프트 1건**) | ✅ |
| [2단계](#2단계-cancelorder-outcome-4가지--멱등-분기-제거-실험) | Outcome 4가지 자연어 응답 (3/4 관찰 성공 — NOT_FOUND는 LLM이 호출 회피) + **멱등 분기 제거판으로 *"이미 취소된 1238에 '조리가 시작된 후 취소 제한'" 거짓 안내*** 사고 재현 + 고객 오해 3·프로덕션 장애 3·Outcome enum 설계 답변 | ✅ (§3 이후 측정 — *§3 finding을 활용해 BaedalPrompt 룰 임시 적용*) |
| [3단계](#3단계-tool-description-실험--미션getdeliverystatus-abc--형태위계-확장) | **§3-b(미션)** 빈약·오해유발 getDeliveryStatus 3버전 A/B/C 정량 비교 + **§3-a(확장)** description 형태 4종(긴-Korean/짧음/영어/예시) + **§3-c(확장)** description ↔ BaedalPrompt 충돌 2건 — 미션 + 확장 6변형 정량 측정. *description은 어떤 형태로도 효과 미약, 유일하게 효과 본 건 BaedalPrompt 메타룰* / *충돌 시 상호배타→회피, 합성가능→합성 가설 실측* | ✅ |
| 4단계 | (예정) Tool 예외 시 에이전트 폴백 설계(`confidenceLevel=LOW` → 상담사 이관 등) | ⏳ |

## 1단계: Tool 3개 구현 + Mock 데이터 확장

**목표**: `@Tool` 3개를 구현하고, 5종 시나리오로 호출이 정확히 분기되는지 검증한다.

### 구현 개요

- **Mock 6건** (`OrderMockService.seed`) — 기존 2건(`2024-1234` DELIVERING / `2024-1235` CREATED) + 신규 4건:
  - `2024-1236` DELIVERED — 배달일 완료(취소 불가 검증용)
  - `2024-1237` COOKING — `NOT_CANCELABLE` 검증용
  - `2024-1238` 사전 CANCELED — 생성 직후 `cancel("고객 요청", now-8m)` 호출해 `canceledReason`·`canceledAt` 채움 → `ALREADY_CANCELED` 멱등 분기 검증용
  - `2024-1239` ACCEPTED — `CANCELED` 경로(취소 가능) 검증용
- **Tool 3개** (`OrderTools.java`) — `@Tool description`은 한국어 multi-line. 각 description에 (1)무엇/(2)언제 호출/(3)입력 형식 `YYYY-XXXX`/(4)null 또는 outcome 분기를 명시.
  - `getOrderDetail` / `getDeliveryStatus` — 없는 주문이면 `null` 반환(예외 X — LLM이 fallback할 여지)
  - `cancelOrder` — 4분기(`CANCELED` / `ALREADY_CANCELED` / `NOT_CANCELABLE` / `NOT_FOUND`)를 *예외가 아닌 결과 값*으로 표현, 멱등성 보장
- **빈 분리** (`ChatClientConfig.java`) — Round 1에서 정착한 *엔드포인트당 1 ChatClient 빈* 원칙 유지. `assistantChatClient` 빈 신규 추가, `AssistantController`는 `@Qualifier` 주입(매 요청 `builder.build()` 안티패턴 회피). `supportChatClient`에도 `.defaultTools(orderTools)` 추가 — Structured Output + Tool Calling 동시 사용.

### 검증 — 시나리오 5종 × 3 라운드 = 15 호출

`POST /api/v1/assistant` (자연어 응답 / Tool 흐름 관찰용 엔드포인트). **동일 조건**(BaedalPrompt 베이스 = `[Tool 사용 규칙]` 없음, OrderTools 한국어 long description) **× 3 라운드** — *Tool 호출 변동성*과 *언어 드리프트 재현률* 을 정량화.

#### Round 1

| # | 시나리오 | Tool 호출 | 응답 결과 |
|---|---|---|---|
| 1 | 배달 (`2024-1234`) | ✅ `getDeliveryStatus(2024-1234)` | *"현재 **라이der**는 역삼역 사거리 부근에서 배달 중…"* — ⚠ **가나/외문 드리프트** (`라이der`, 영문 알파벳) |
| 2 | 메뉴 (`2024-1234`) | ✅ `getOrderDetail(2024-1234)` | *"허니콤보와 콜라 1.25L였습니다"* — 정확 |
| 3 | 취소 CREATED (`2024-1235`) | ✅ `cancelOrder(2024-1235, reason=고객 요청)` | **CANCELED** — *"주문이 성공적으로 취소되었습니다. 취소 사유는 '고객 요청'"* |
| 4 | 취소 DELIVERED (`2024-1236`) | ❌ Tool 0회 | 되묻기 — *"주문을 취소할까요? 그 이유는 무엇인가요?"* |
| 5 | 없는 주문 (`2099-9999`) | ✅ `getDeliveryStatus(2099-9999)` → null | *"주문번호를 다시 확인해 주세요"* — 정확 |

#### Round 2

| # | 시나리오 | Tool 호출 | 응답 결과 |
|---|---|---|---|
| 1 | 배달 (`2024-1234`) | ✅ `getDeliveryStatus(2024-1234)` | *"현재 **라이ダー**는 역삼역 사거리…"* — ⚠ **가나 드리프트** (`라이ダー`, 가타카나) |
| 2 | 메뉴 (`2024-1234`) | ✅ `getOrderDetail(2024-1234)` | *"허니콤보와 콜라 1.25L"* — 정확 |
| 3 | 취소 CREATED (`2024-1235`) | ✅ `cancelOrder(2024-1235, reason=고객 요청)` | **ALREADY_CANCELED** † — *"주문이 이미 취소되었습니다. 이전에는 고객님께서 직접 취소"* |
| 4 | 취소 DELIVERED (`2024-1236`) | ✅ `cancelOrder(2024-1236, reason=고객 요청)` | **NOT_CANCELABLE** — *"현재 상태(DELIVERED)에서는 취소가 불가합니다. 조리 시작 이후엔 취소가 제한"* 정확 |
| 5 | 없는 주문 (`2099-9999`) | ✅ `getDeliveryStatus(2099-9999)` → null | *"주문번호 2099-9999를 다시 확인해주세요"* |

† R2.3 — R1.3에서 같은 1235가 이미 CANCELED 상태가 된 후의 후속 호출. `cancelOrder` 멱등 분기가 정상 동작해 `ALREADY_CANCELED` outcome 자연 트리거 + 응답에 `canceledReason="고객 요청"` 까지 정확 반영. **§2 멱등 분기 코드 검증을 자연 발생 조건에서 확인한 부수 finding**.

#### Round 3

| # | 시나리오 | Tool 호출 | 응답 결과 |
|---|---|---|---|
| 1 | 배달 (`2024-1234`) | ❌ Tool 0회 | **raw JSON 본문 노출** — *"ionales\n{\"name\":\"getDeliveryStatus\",\"arguments\":{\"orderId\":\"2024-1234\"}}\</tool_call\>"* — **tool protocol 파손** |
| 2 | 메뉴 (`2024-1234`) | ✅ `getOrderDetail(2024-1234)` | *"교촌치킨 강남점에서 허니콤보와 콜라 1.25L"* — 정확 + storeName 자연 추가 |
| 3 | 취소 CREATED (`2024-1235`) | ❌ Tool 0회 | 되묻기 — *"확인 후 안내드리겠습니다. 주문을 취소하는 것이 맞습니까?"* |
| 4 | 취소 DELIVERED (`2024-1236`) | ❌ Tool 0회 | 되묻기 — *"주문 번호를 다시 한번 확인해주실 수 있으신가요? 또한 취소 사유도 말씀해 주시면"* |
| 5 | 없는 주문 (`2099-9999`) | ✅ `getDeliveryStatus(2099-9999)` → null | *"주문번호를 다시 확인해 주세요"* |

#### 시나리오별 호출 성공률

| # | 시나리오 | R1 | R2 | R3 | 호출 성공률 |
|---|---|:-:|:-:|:-:|:-:|
| 1 | 배달 | ✅ | ✅ | ❌ raw JSON | **2/3 (67%)** |
| 2 | 메뉴 | ✅ | ✅ | ✅ | **3/3 (100%)** |
| 3 | 취소 CREATED | ✅ CANCELED | ✅ ALREADY_CANCELED | ❌ 되묻기 | **2/3 (67%)** |
| 4 | 취소 DELIVERED | ❌ 되묻기 | ✅ NOT_CANCELABLE | ❌ 되묻기 | **1/3 (33%)** |
| 5 | 없는 주문 | ✅ | ✅ | ✅ | **3/3 (100%)** |

#### Tool 호출 분포 (15 호출 종합)

| 분류 | 비율 | 사례 |
|---|---:|---|
| ✅ 정상 Tool 호출 + 응답 | **11/15 (73%)** | 시나리오 2·5 전체, 시나리오 1 R1·R2, 시나리오 3 R1·R2, 시나리오 4 R2 |
| ❌ Tool 0회 + 되묻기 | **3/15 (20%)** | R1.4, R3.3, R3.4 |
| ❌ Tool 0회 + **raw JSON 본문 파손** | **1/15 (7%)** | R3.1 |

### LLM 지연·토큰 (PerformanceLoggingAdvisor 실측, 15 호출 종합)

| 그룹 | 호출 수 | 평균 지연 | 입력 토큰 (대표) | 비고 |
|---|---:|---|---:|---|
| Tool 호출 + 응답 (2 라운드) | 11 | cold 135s(R1.1), warm 8~17s | ~4,500 | Tool 결과 받아 2nd LLM 라운드 |
| Tool 0회 회피/되묻기 (1 라운드) | 3 | 2.7~3.9s | ~2,200 | 단일 LLM 라운드 |
| Raw JSON 본문 파손 (1 라운드) | 1 | 2.8s | 2,200 | 단일 LLM 라운드, Tool 우회 |

- **cold 135s → warm 8~17s** — qwen2.5 로컬 환경 KV 캐시 영향 큼. cold 시간은 측정 세션마다 다름(시스템 thermal/메모리 상태 영향).
- **입력 토큰 ~4,500** — BaedalPrompt + Tool 3개 description/schema. Tool 호출 안 한 케이스는 *2nd 라운드가 통째로 없어* 입력 토큰이 절반(~2,200) — 호출 여부만으로도 토큰 비용 2배 차이.
- 입력 토큰 4,000+는 *Round 1 4단계(589→1248) 대비 한 단계 더 늘었다*. Tool 추가의 토큰 비용이 명백 — §1 결정 #3(Tool 6개 분리 트리거)의 정량 근거.

### 핵심 Finding 6건 — *Tool calling은 자동이 아니고, 변동성도 크다*

1. **시나리오 4(DELIVERED 취소) 최난이도 — 33% 호출률 (1/3)** : 명백히 거절 가능한 케이스(`NOT_CANCELABLE` outcome 받을 수 있는 발화)에서도 모델이 *Tool로 가지 않고 사람에게 사유 묻기*로 빠짐 — **취소-불가 사전 휴리스틱**이 작동하는 듯. *R2.4 1건*만 정상 호출 + `NOT_CANCELABLE` outcome 정확 반영. 3건 중 2건 회피는 Tool 사용 패턴의 *가장 약한 고리*.

2. **시나리오 3(CREATED 취소) 67% 호출률 (2/3) — 변동성 큼, 초기 단발 측정의 0/1 실패는 단일 노이즈 가능성** : 3회 중 2회 정상 호출(R1.3 CANCELED, R2.3 ALREADY_CANCELED), 1회는 *되묻기*(R3.3). 본 README 초안의 *"qwen2.5 조회형 편향"* finding(시나리오 3·4가 둘 다 0/1 실패였던 단발 측정에서 도출) 일반화는 *단일 시점 노이즈일 가능성*을 정량 확인. *"한 번 측정으로 일반화 금지"* 가 가장 큰 메타 교훈.

3. **시나리오 1 가나/외문 드리프트 — Tool 호출 성공한 케이스에서 100% 재현 (2/2)** : 3회 측정 중 Tool 호출 성공한 2건 모두 외래어 드리프트.
   - R1.1: `라이der` (영문 알파벳)
   - R2.1: `라이ダー` (가타카나)
   - **"라이"는 항상 한글로 시작 + 다음 음절(`더`)에서 100% 외래어 표기로 새는 패턴**. 어디서 새는지(`라이`)는 일관, *어느 언어로 새는지는 변동*(가타카나/영문).

   **왜 새는가 — 토큰 단위 다국어 오염(token-level multilingual contamination) 가설** *(3회 데이터로 강화됨)*

   *문장-수준 언어 전환 결함이 아니다.* 전체 문장이 외국어로 가지 않고 **한 단어 안에서 음절 1~2개만** 새는 패턴이 3/3 재현.

   - **트리거는 단어 "라이더" 자체** : 한국어 `라이더` ↔ 일본어 `ライダー` ↔ 영어 `rider` 모두 *같은 외래어*. qwen2.5는 세 표기를 학습 데이터에서 사실상 동률에 가깝게 봤음. 모델이 `라이`까지 한글로 쓴 뒤 다음 토큰 후보 분포에서 `더`(한국어 종결), `ダー`(일본어 가타카나), `der`(영어 알파벳) 의 확률이 *비정상적으로 가깝게* 잡힘 — 외래어이기 때문에 발생한 사고. 다른 일반 한국어 단어("배달", "주문")는 등가 외국어가 멀리 떨어져 있어 이런 일이 안 일어남.
   - **Tool 응답 흐름에서 *유독* 약해진 4가지 이유**:
     1. **시스템 프롬프트 attention decay** — Round 1은 *system → user → assistant* 짧은 컨텍스트. Round 2는 *system → user → assistant.tool_call → **tool.result(JSON)** → assistant.final*. 중간에 영문 enum(`DELIVERING`)·JSON 구조가 끼면서 "한국어로만" 룰의 attention weight가 희석.
     2. **입력 토큰 4,000+** — BaedalPrompt + Tool 3개 description/schema가 매 요청 들어가 *시스템 프롬프트 비중*이 상대적으로 낮아짐 — 룰이 *더 멀리* 있게 됨.
     3. **Structured Output ↔ Free Text** — Round 1 §3 검증은 `SupportResponse` JSON 스키마 안에서 이뤄짐(필드 단위 제약). Round 2 자유 텍스트는 *토큰 단위 언어 게이트 없음*. 룰은 같지만 *실행 강제력*이 다름.
     4. **Tool result 내 영문/숫자 혼재** — `status="DELIVERING"`, 시각 문자열 같은 비한국어 토큰을 거치면서 모델의 *언어 모드 잠금*이 일시적으로 풀림.

   **Round 1 룰의 사정거리 밖인 이유** — Round 1 [규칙] "한국어로만"은 **문장-수준 언어 선택**을 통제. 이번 사고는 **subword 토큰 샘플링 단계의 통계적 사고** — 통제 입자가 다른 차원. 룰 결함이 아니라 *룰이 다루지 않는 층위*의 문제.

   **방어 옵션 4가지** (강도·비용 trade-off):
   - (a) Tool description·result에 *한국어 표기 고정 어휘 사전*("라이더는 항상 한글로") — 룰을 *단어 단위*로 끌어내림. 효과 보통.
   - (b) 응답 post-processing — 가나/한자 등장 시 한국어 표준 표기로 결정론적 치환. 가장 강력, 가장 무거움.
   - (c) 샘플링 온도 낮춤 / top-k 좁힘 — 외래어 동률 분포의 낮은 확률 후보를 자름. 간단하지만 응답 다양성 손해.
   - (d) 운영 알람만 — 100% 재현(2/2)이지만 의미 손상 없음 → 룰 변경 없이 모니터링만. 가장 저비용.

4. **세션 내 측정 위치도 변동성 요인** *(신규)* : 재측정 Round 3에서 5건 중 4건이 비정상(Tool 미호출 3건 + raw JSON 1건). 응답 시간도 2.7~8.9s로 매우 짧음(단일 LLM 라운드). **동일 서버 세션의 KV cache가 *되묻기/회피 패턴*으로 굳어져 reasoning이 *지름길*로 단순화된 것**으로 추정. → 측정 신뢰도 위해 *라운드 간 서버 재시작 또는 KV cache flush* 가 필요한 새 finding. *측정 자체에도 reproducibility 함정이 있음*.

5. **R2.3 `ALREADY_CANCELED` 자연 트리거 — §2 멱등 분기의 자연 발생 검증** *(신규)* : R1.3에서 1235 취소 → R2.3에서 같은 1235에 cancelOrder 재호출 → outcome `ALREADY_CANCELED` 정확 + 응답에 *"이전 사유: 고객 요청"* 반영. §2-A 측정은 *사전 CANCELED인 1238* 로만 검증했는데, 이번에 *같은 세션 내 두 번 호출* 시나리오에서도 멱등 분기가 정상 작동 — **§2-B(멱등 분기 제거) 사고와 정반대 방향의 자연 발생 검증**.

6. **Tool protocol 파손, qwen2.5에서 *무시 못할* 빈도** *(신규)* : R3.1(7%, 본 측정 1/15) + §3 variant C(영어 long, 17%, 1/6) + 사후 EN-3(50%, 1/2) — **누적 3건**. 응답 본문에 `{"name":"...","arguments":{...}}` raw function-call JSON이 그대로 흘러나옴. *Tool 시스템 자체가 우회*되는 운영 사고 패턴 — UI에 raw JSON 노출되는 *시각적 사고*이자 *실제 호출이 일어나지 않는 정합성 사고*. **운영 알람 후보 1순위**.

→ 2·3단계의 직접 입력이 됨: 2단계는 멱등성, **3단계는 description 변형을 통한 Tool 선택 정확도 끌어올리기**(시나리오 3·4 해소가 목표).

### Raw 로그

> [raw/stage1-scenarios.txt](raw/stage1-scenarios.txt) — 동일 조건 × 3 라운드 × 5 시나리오 = **15 호출 응답 본문 + Tool 호출 시퀀스 + LLM 지연·토큰 원문**.

### 사후 실험 — BaedalPrompt에 `[Tool 사용 규칙]` 박으면 위 finding 개선되나?

**동기** — Finding 1·2(시나리오 3·4 cancelOrder 호출 변동성)와 Finding 3(가나 드리프트 100%)를 BaedalPrompt 시스템 프롬프트의 *룰*로 강제하면 개선되는지 검증. §3 variant A에서 본 *룰 효과(33% 호출률)* 가 동일 조건에서 재현되는지도 같이 본다.

#### 실험 1 — 룰 7항목 (강한 명령형)

7항목 룰을 `BaedalPrompt`의 `[규칙]` 다음 `[Tool 사용 규칙]` 섹션으로 추가. *"반드시", "필수", "직접 호출"* 같은 강한 명령어 포함.

```text
[Tool 사용 규칙]
- 발화에 주문번호(YYYY-XXXX 형식, 예: 2024-1234)가 포함되면 반드시 주문 관련 Tool을 호출합니다. 추측·짐작으로 답하지 않습니다.
- "취소", "캔슬", "물러주세요" 등 취소 의도 키워드가 발화에 명시되면 반드시 cancelOrder Tool을 호출합니다. getOrderDetail로 상태만 확인하고 갈음하지 않습니다 — 정책 분기(NOT_CANCELABLE / ALREADY_CANCELED 등)는 cancelOrder의 outcome으로 응답해야 합니다.
- "배달 상태·위치·도착시간"을 묻는 발화는 getDeliveryStatus 를 호출합니다.
- "메뉴·금액·주문 상태"를 묻는 발화는 getOrderDetail 을 호출합니다.
- 일반 인사("안녕하세요", "고맙습니다")에는 Tool을 호출하지 않습니다.
- 정책 질문("환불 가능해요?", "취소 정책이 뭐예요?")처럼 특정 주문번호 없이 일반 정책을 묻는 발화에는 Tool 없이 정책 텍스트로만 답합니다.
- Tool 결과의 status·outcome 필드는 그대로 본문에 반영합니다. 임의로 다르게 해석·재서술하지 않습니다 (예: status=CREATED 인 결과를 "이미 취소되었습니다" 라고 답해서는 안 됩니다).
```

**결과 (5종 × 3 라운드 = 15 호출, raw: [stage1-scenarios.txt](raw/stage1-scenarios.txt) — *조건 B* 섹션)**

- 정상 Tool 호출: **7/15 (47%)** — 베이스 73% 대비 **-26%p 악화**
- 시나리오 4 (DELIVERED 취소): **0/3** — 완전 실패 (베이스 1/3)
- 시나리오 3 (CREATED 취소): 1/3 — 베이스 2/3에서 악화
- **신규 파손 패턴 1: 본문에 가짜 `cancelOrder` syntax** *(2/15)* — `)((((cancelOrder {"orderId":"2024-1235","reason":"고객 요청"})))` 형태로 *호출 흉내* 텍스트 노출, 실제 Tool 미호출. *promise hallucination의 진화형*.
- **신규 파손 패턴 2: 본문에 orderId만 박고 호출 약속만** *(2/15)* — *"orderid: 2024-1234 / 주문 내역을 조회하겠습니다"*
- 가나 드리프트는 **100% → 33%로 줄어듦** *(의도하지 않은 부수 효과)*
- 입력 토큰 +650 (~5,150)

#### 실험 2 — 룰 2항목 (사실 진술형, *반드시/필수 등 강제 어휘 제거*)

7항목의 *상세함/강제성*이 역효과인지 검증. *Goodhart's Law* 가설 — *"반드시 호출하라"* 같은 강한 metric은 모델이 *호출 흉내*로 만족시키는 길로 빠질 수 있음. 핵심만 2줄로 압축 + 강제 어휘 모두 제거.

```text
[Tool 사용 규칙]
- 주문번호가 발화에 있으면 적절한 주문 Tool을 호출해 처리합니다.
- Tool 결과의 값은 그대로 안내에 반영합니다.
```

**결과 (5종 × 3 라운드 = 15 호출, raw: [stage1-scenarios.txt](raw/stage1-scenarios.txt) — *조건 C* 섹션)**

- 정상 Tool 호출: **10/15 (67%)** — 7항목 47%에서 **+20%p 회복**, 베이스 73%엔 -6%p
- 시나리오 4 (DELIVERED): **1/3 (베이스 회복)** — R1.4 *"이미 배달 완료(Delivered) 상태…조리가 시작된 후에는 취소가 제한"* — `NOT_CANCELABLE` outcome 정확 반영
- 시나리오 3 (CREATED): **0/3** — 베이스 2/3 → 7항목 1/3 → 2항목 0/3, *룰 형태와 무관하게 룰 존재 자체가 시나리오 3을 악화*
- **신규 파손 패턴 3: form-input bracket** *(2/15)* — *"[취소 사유를 입력해 주세요 (선택사항)]"* — 대화형 챗봇에 *form input UI*를 흉내내는 응답. **룰 간소화가 새 변종 파손을 만든다** — 사라진 게 아니라 *형태가 바뀐다*.
- 7항목에서 나왔던 **가짜 cancelOrder syntax + raw JSON 파손은 둘 다 0건으로 사라짐** ✓
- 가나 드리프트 100% → 33% (7항목과 동일)
- 입력 토큰 +100 (~4,600)

#### 3조건 비교

| 지표 | 베이스 (룰 없음) | 룰 7항목 | **룰 2항목 (채택)** |
|---|---:|---:|---:|
| 정상 Tool 호출 | **11/15 (73%)** | 7/15 (47%) | 10/15 (67%) |
| Tool 0회 회피/되묻기 | 3/15 (20%) | 3/15 (20%) | 4/15 (27%) |
| Tool 0회 + raw JSON 파손 | 1/15 (7%) | 1/15 (7%) | **0** ✓ |
| Tool 0회 + 가짜 cancelOrder syntax | 0 | 2/15 (13%) | **0** ✓ |
| Tool 0회 + promise hallucination | 0 | 2/15 (13%) | 1/15 (7%) |
| Tool 0회 + form-input bracket *(2항목 신규)* | 0 | 0 | 2/15 (13%) |
| **시나리오 1 가나/외문 드리프트** | **2/2 (100%)** | 1/3 (33%) | **1/3 (33%)** |
| 시나리오 3 (CREATED 취소) | 2/3 (67%) | 1/3 (33%) | **0/3 (0%)** |
| 시나리오 4 (DELIVERED 취소) | 1/3 (33%) | 0/3 (0%) | **1/3 (33%)** |
| 입력 토큰 (Tool 호출 시) | ~4,500 | ~5,150 (+650) | **~4,600 (+100)** |

#### 핵심 Finding 6건 (사후 실험)

1. **Goodhart's Law 가설 부분 입증** : 룰 7항목의 *상세함/강제성*("반드시", "필수", "직접 호출")이 *호출 흉내 사고*를 유발 → 강제 어휘 제거 + 2줄 압축으로 raw JSON·가짜 cancelOrder syntax 두 패턴 **모두 사라짐**(3건 → 0건). **강한 metric은 흉내로 만족되기 쉽다**는 패턴 정량 확인.

2. **룰 존재 자체가 시나리오 3을 악화 *(별개 메커니즘)*** : 호출률 67% → 33% → **0%**로 룰 형태와 무관하게 단조 감소. 가설: *"적절한 주문 Tool"* 같은 추상화가 *cancelOrder를 *고를* 부담*을 만들어 회피로 빠짐. Goodhart와 별개로 *룰 존재 자체* 의 비용.

3. **새 파손 패턴 form-input bracket** : *"[취소 사유를 입력해 주세요 (선택사항)]"* — 룰 간소화가 promise hallucination을 *form input 흉내*로 변형. **파손은 사라지지 않고 형태가 바뀐다** — 룰 디자인의 *whack-a-mole* 본성 확인.

4. **가나 드리프트 100% → 33% (룰 형태 무관)** : 7·2항목 모두 동일 개선. 룰의 *"Tool 결과 값 그대로"* 조항이 *짧고 사실 위주* 응답을 유도해 *라이더 디테일을 우회*하게 만듦 (R2.1·R3.1는 *"배달은 현재 역삼역"* 으로 라이더 단어 자체 생략). **Finding 3의 방어 옵션 (a)** *한국어 표기 고정 어휘 사전* 의 약식 검증.

5. **시나리오 4(DELIVERED) 베이스 회복** : R1.4 *"이미 배달 완료(Delivered) 상태…조리가 시작된 후에는 취소가 제한"* — `NOT_CANCELABLE` outcome 정확 + 한국어 정상. 7항목(0/3)에서 망가졌던 게 2항목으로 회복. *"Tool 결과 그대로 반영"* 조항의 진짜 효과.

6. **세션 위치 변동성은 룰과 무관한 환경 요인 *(재확인)*** : 2항목 R3.1 응답 217초. 베이스·7항목 라운드의 Round 3 비정상(Finding 4 §1단계)이 룰 2항목에서도 재현. **반복 측정 시 라운드 간 KV cache 영향**은 룰 디자인으로 못 막는 인프라 issue.

#### 한 줄 결론 *(천장 finding)*

> qwen2.5 7B + 본 환경에서 **룰을 어떻게 박아도 *호출률* 은 베이스보다 못 미치고, 다만 *어떤 파손이 나오는지* 의 형태만 바뀐다**. 7항목 = raw JSON·가짜 syntax / 2항목 = form-input bracket. 룰 자체의 한계라기보다 *모델 능력의 천장*. 더 작은 모델로는 *호출률을 룰로 향상* 불가능 — 더 큰 모델 또는 fine-tuning 필요.

#### 채택한 코드 상태

**룰 7항목 유지** (`BaedalPrompt.java`) — *정량 우위는 룰 2항목·베이스(67%)지만 사용자 결정으로 7항목 채택*:
- **사유**: 7항목은 description의 발화 매핑(취소 키워드 → cancelOrder, 배달 키워드 → getDeliveryStatus 등)을 *명시적*으로 박은 버전 — 시스템 프롬프트가 *비즈니스 가이드 의도* 를 그대로 표현하는 게 유지보수성·리뷰 시 직관에 더 부합.
- **수용한 비용**: 호출률 -26%p (47%) + 입력 토큰 +650 + 새 파손 패턴 (가짜 cancelOrder syntax 2건). *모델 능력 천장* 이라는 finding이 있는 한, 룰 형태로 추가 개선 불가능 → *룰 디자인 비용*보다 *문서화 의도* 우선.
- **시나리오 3·4 호출률 약점은 §4단계(Tool 예외 폴백)에서 별도 트랙으로 다룬다** — 룰로 못 고치는 영역이라는 게 finding 6의 *천장* 메시지.

### 설계 결정

#### 1. `OrderDetailView` 가 `Order`에서 의도적으로 뺀 필드

- 뺀 필드: `deliveryAddress`, `riderLocation`, `canceledReason`, `canceledAt`
- 이유:
  - **`deliveryAddress`** — 주소는 **PII**. 메뉴/금액 조회용 DTO에 굳이 노출할 이유가 없고, LLM 컨텍스트로 흘려보내는 순간 응답·로그에 누설될 위험이 커진다.
  - **`riderLocation`** — *Tool 책임 분리*. 라이더 위치는 `getDeliveryStatus`의 책임이고, OrderDetail에 섞으면 두 의도(메뉴 조회 ↔ 배달 추적)가 한 도구에 묶여 LLM이 잘못된 Tool을 선택하기 더 쉬워진다(이번 시나리오 3·4의 fragility 가속 우려).
  - **`canceledReason`·`canceledAt`** — 취소 이력은 이미 `cancelOrder` 의 `ALREADY_CANCELED` 응답 `message`에 담는다. DetailView에 또 박으면 같은 정보가 두 경로로 흘러 일관성·테스트 부담만 늘어남.
- 부수효과: 4개 필드를 빼면서 매 요청 직렬화 토큰 비용도 그만큼 줄어듦.

#### 2. `@Tool description` 언어 — **한국어**

- 근거:
  - 사용자 발화가 한국어 → 모델이 한국어 컨텍스트로 reasoning. description도 한국어여야 *발화-도구 의미 거리* 가 가장 짧다.
  - qwen2.5는 중국어·영어 능력이 강하지만, 본 봇은 한국 CS 도메인. 도구 설명이 영어면 *발화(한국어) → reasoning(한국어) → 도구 선택(영어)* 사이에 한 단계 번역이 끼어 분기 정확도가 떨어질 수 있다고 판단.
- 단 한국어 description으로도 시나리오 3·4가 실패했음 → 3단계에서 *영어 description / 짧은 description / 사용 예시 포함 description* 변형으로 어느 형태가 분기 정확도를 끌어올리는지 측정 예정.

##### 2-사후. 클린 A/B 검증 — 한국어 long vs 영어 long mirror (변수 통제)

§3 변형 C 측정은 *description(영어) + BaedalPrompt([Tool 사용 규칙] 추가)* 두 변수가 동시에 바뀌어 *영어 description 자체의 효과*를 분리하지 못했다. 위 결정의 근거를 검증하기 위해 **단일 변수**(description 형태만 한국어 long ↔ 영어 long *진정한 mirror*)로 A/B를 재측정.

**실험 조건**
- BaedalPrompt: `[Tool 사용 규칙]` 제거 — §1단계 베이스와 동일
- 사용자 발화: 한국어 그대로 (절대 변경 없음)
- OrderTools description + ToolParam: 한국어 long(4요소) 측정 후, *같은 내용을 영어로 그대로 옮긴 mirror* 로 swap 후 재측정
- 측정 시나리오: §1단계의 시나리오 1(배달) + 3(취소) — *Tool 분기*와 *언어 드리프트* 두 가지 finding을 가장 잘 노출

**한국어 long description — A/B의 KR 측 (베이스, `OrderTools.java` 현행)**

```java
@Tool(description = """
        주문번호로 주문 상세(매장명, 메뉴 목록, 총액, 상태, 주문시각, 예상 도착시각)를 조회합니다.
        호출 시점: 고객이 "어떤 메뉴 시켰지?", "얼마였지?", "주문 상태가 뭐예요?" 등 주문 내역·금액·상태를 묻는 경우.
        [필수] 위 키워드가 발화에 명시되고 주문번호(YYYY-XXXX)가 있으면 반드시 이 Tool을 호출합니다. 추측·짐작으로 답하지 마세요.
        입력 형식: orderId 는 "YYYY-XXXX" (예: 2024-1234).
        반환: 존재하지 않는 주문이면 null. 이 경우 응답에서 "주문번호를 다시 확인해 주세요"라고 안내하세요.
        라이더 위치는 이 Tool이 아닌 getDeliveryStatus 를 사용하세요.
        """)
public OrderDetailView getOrderDetail(
        @ToolParam(description = "조회할 주문번호. 형식 'YYYY-XXXX' (예: 2024-1234).") String orderId) { ... }

@Tool(description = """
        주문번호로 배달 상태와 라이더 위치를 조회합니다.
        호출 시점: 고객이 "배달 어디쯤이에요?", "라이더 어디 있어요?", "언제 도착해요?" 등 배달 진행 상황을 묻는 경우.
        [필수] 위 배달 키워드가 발화에 명시되고 주문번호(YYYY-XXXX)가 있으면 반드시 이 Tool을 호출합니다. 추측·짐작으로 답하지 마세요.
        riderLocation 필드는 status=DELIVERING 일 때만 유효하며, 그 외 상태에서는 null 또는 의미 없는 값일 수 있습니다.
        메뉴·금액 조회가 목적이면 이 Tool이 아닌 getOrderDetail 을 사용하세요.
        입력 형식: orderId 는 "YYYY-XXXX". 존재하지 않는 주문이면 null 을 반환합니다.
        """)
public DeliveryStatusView getDeliveryStatus(
        @ToolParam(description = "조회할 주문번호. 형식 'YYYY-XXXX' (예: 2024-1234).") String orderId) { ... }

@Tool(description = """
        주문을 취소합니다.
        호출 시점: 고객이 "취소", "캔슬", "물러주세요" 같은 취소 의도 키워드로 명시적 취소를 요청할 때.
        [필수] 위 키워드가 발화에 명시되고 주문번호(YYYY-XXXX)가 있으면 반드시 이 Tool을 직접 호출합니다. getOrderDetail로 상태만 확인하고 갈음하지 마세요 — 정책 분기(NOT_CANCELABLE, ALREADY_CANCELED, NOT_FOUND 등)는 이 Tool의 outcome으로 반환됩니다. 존재하지 않을 것 같은 주문번호여도 직접 호출해 NOT_FOUND outcome을 받으세요.
        취소 가능 조건: 상태가 CREATED(주문 직후) 또는 ACCEPTED(사장님 수락 직후)일 때만 가능합니다.
        취소 불가: COOKING(조리 시작)·DELIVERING·DELIVERED 상태는 취소할 수 없습니다.
        멱등성: 이미 취소된 주문을 다시 요청하면 예외가 아니라 outcome=ALREADY_CANCELED 를 반환합니다 — 동일 요청 반복도 안전합니다.
        결과 해석(CancelOrderResult.outcome):
          CANCELED          → "주문이 취소되었습니다"
          ALREADY_CANCELED  → "이미 취소된 주문입니다" (사유가 message 에 포함되면 함께 안내)
          NOT_CANCELABLE    → "조리가 시작되어 취소할 수 없습니다"
          NOT_FOUND         → "주문번호를 찾을 수 없습니다"
        """)
public CancelOrderResult cancelOrder(
        @ToolParam(description = "취소할 주문번호. 형식 'YYYY-XXXX' (예: 2024-1234).") String orderId,
        @ToolParam(description = "취소 사유(한국어 자연어). 고객이 사유를 말하지 않으면 '고객 요청'으로 채우세요.") String reason) { ... }
```

**적용한 영어 mirror description (3개 Tool 모두 한국어 4요소 풀버전을 그대로 영어로 옮김)**

```java
@Tool(description = """
        Fetch order details (store name, menu items, total amount, status, ordered time, estimated delivery time) by order ID.
        When to call: when the customer asks "what menu did I order?", "how much was it?", "what's the order status?" — questions about order contents, amount, or status.
        [Required] If the above keywords appear in the utterance and an order ID (YYYY-XXXX) is present, you MUST call this tool. Do not guess or speculate.
        Input format: orderId is "YYYY-XXXX" (e.g., 2024-1234).
        Returns: null if the order does not exist. In that case, respond with "Please check the order number again."
        For rider location, use getDeliveryStatus, not this tool.
        """)
public OrderDetailView getOrderDetail(
        @ToolParam(description = "Order ID to look up. Format 'YYYY-XXXX' (e.g., 2024-1234).") String orderId) { ... }

@Tool(description = """
        Fetch delivery status and rider location by order ID.
        When to call: when the customer asks "where is the delivery?", "where is the rider?", "when will it arrive?" — questions about delivery progress.
        [Required] If the above delivery keywords appear in the utterance and an order ID (YYYY-XXXX) is present, you MUST call this tool. Do not guess or speculate.
        The riderLocation field is meaningful only when status=DELIVERING; in other states it may be null or meaningless.
        If the goal is to look up menu or amount, use getOrderDetail, not this tool.
        Input format: orderId is "YYYY-XXXX". Returns null if the order does not exist.
        """)
public DeliveryStatusView getDeliveryStatus(
        @ToolParam(description = "Order ID to look up. Format 'YYYY-XXXX' (e.g., 2024-1234).") String orderId) { ... }

@Tool(description = """
        Cancel an order.
        When to call: when the customer explicitly requests cancellation using keywords like "cancel", "cancel order", "I want to cancel".
        [Required] If the above keywords appear in the utterance and an order ID (YYYY-XXXX) is present, you MUST call this tool directly. Do not substitute by only calling getOrderDetail to check status — policy branches (NOT_CANCELABLE, ALREADY_CANCELED, NOT_FOUND) are returned as the outcome of this tool. Even for order IDs that look unlikely to exist, call this tool directly to receive the NOT_FOUND outcome.
        Cancelable conditions: only when status is CREATED (right after order) or ACCEPTED (right after store accepts).
        Not cancelable: COOKING (cooking started), DELIVERING, DELIVERED states cannot be cancelled.
        Idempotency: requesting cancellation on an already-cancelled order returns outcome=ALREADY_CANCELED instead of throwing an exception — repeated identical requests are safe.
        Result interpretation (CancelOrderResult.outcome):
          CANCELED          → "Order has been cancelled"
          ALREADY_CANCELED  → "Order was already cancelled" (include reason from message if present)
          NOT_CANCELABLE    → "Cooking has started, cannot cancel"
          NOT_FOUND         → "Order number not found"
        """)
public CancelOrderResult cancelOrder(
        @ToolParam(description = "Order ID to cancel. Format 'YYYY-XXXX' (e.g., 2024-1234).") String orderId,
        @ToolParam(description = "Cancellation reason (free-form natural language). If the customer does not specify a reason, fill with 'customer request'.") String reason) { ... }
```

> 한국어 원본 ↔ 영어 mirror 사이에 4요소 구성(*무엇/언제/입력/실패*), 문장 구조, [Required] 강조, 다른 Tool 안내 문구까지 1:1 대응. *형태 동등성 + 언어만 다름* 으로 통제.

**결과** (시나리오 1·3 × 한국어/영어 = 총 4호출)

| 측정 | 시나리오 1 (배달) | 시나리오 3 (취소) |
|---|---|---|
| **KR** 한국어 long | ✅ `getDeliveryStatus(2024-1234)` 호출 / LLM 44.7s / 입력 4521 토큰<br>응답: *"현재 **라이ダー**는 역삼역 사거리 부근에서 배송 중입니다…"* — ⚠ **가나 드리프트** 재현(3회 누적) | ✅ `cancelOrder(2024-1235, reason=고객 요청)` 호출 / LLM 13.0s / 입력 4504 토큰<br>응답: *"주문이 취소되었습니다. 취소 사유는 '고객 요청'입니다."* — **정상** |
| **EN** 영어 long mirror | ✅ `getDeliveryStatus(2024-1234)` 호출 / LLM 95.3s / 입력 4157 토큰<br>응답: *"…**배달원**은 현재 역삼역 사거리…"* — **가나 없음** | ❌ `cancelOrder` **미호출** / LLM 7.4s / 입력 2025 토큰<br>응답: *"理解和确认您的需求后，我会帮您取消订单…"* — **중국어 응답** + 본문에 `{"name":"cancelOrder","arguments":{"orderId":"2024-1235","reason":"customer request"}}` raw JSON 노출 |

**Finding 4건**

1. **영어 description → 한국어 발화에 *중국어* 응답** *(예측 못 한 결과)* : 한국어 발화 + 한국어 응답 룰 + 영어 description 조합에서 모델이 **중국어로 답함**. qwen2.5 base가 중국 발 모델이라, 영어 description 토큰이 *내부 reasoning 컨텍스트의 언어 모드*를 중국어로 시프트시킨 것으로 추정. §3 variant C(영어 + 룰 있음)에서는 한국어로 답했었는데 *룰이 없는* 이번 조건에선 중국어로 새는 변동성 확인.
2. **영어 description → tool calling protocol 파손 재현 (cancelOrder)** : §3 variant C와 동일 패턴 — 본문에 raw function-call JSON 노출, 실제 Tool은 미호출. 이번엔 *단일 변수 조건*(BaedalPrompt 룰 없음)에서 재현 — **영어 description 자체가 protocol 파손의 충분 조건**이라는 증거가 더 강해짐.
3. **영어 description은 가나 드리프트는 막는다** *(역설적 trade-off)* : KR-1 `라이ダー` → EN-1 `배달원`. 영어 description이 모델 reasoning을 *완전히 다른 언어 모드*로 끌고 가면서 한국어 외래어 "라이더"의 한·일 표기 동률 분포에 아예 접근하지 않게 됨. **한 finding이 다른 finding을 가린다** — 즉 *가나 드리프트 방어책으로 영어 description을 도입*하면 *protocol 파손 + 중국어 응답*이라는 더 큰 사고가 따라옴.
4. **한국어 baseline의 §1단계 결과는 단일 측정 노이즈로 확정** *(추후 §1단계 3회 재측정으로 closure 됨)* : §1단계 초기 단발 측정에서 KR-3 `cancelOrder`는 0/1 실패였으나, 이 사후 검증(1/1 성공)에 더해 **§1단계 *재측정 3회*에서 시나리오 3은 2/3 성공(R1.3 CANCELED, R2.3 ALREADY_CANCELED), 시나리오 4도 1/3 성공(R2.4 NOT_CANCELABLE)** — 변동성이 정량 확정. § 1단계 *검증 — 시나리오 5종 × 3 라운드* 표 + Finding 1·2 참조. → §1 README 초안의 *"qwen2.5 조회형 편향"* 일반화가 **단일 시점 관찰의 과일반화** 였음 closure.

**입력 토큰 비교**

| | 입력 토큰 (Tool 호출 후) | 출력 토큰 |
|---|---:|---:|
| KR-1 | 4,521 | 124 |
| EN-1 | 4,157 | 109 |
| KR-3 | 4,504 | 142 |
| EN-3 (Tool 미호출, 단일 라운드) | 2,025 | 86 |

→ 영어 mirror가 ~360 토큰(~8%) 짧음. 단순 토큰 효율만 보면 영어가 유리하나, **분기 정확도 + protocol 파손 + 언어 드리프트(중국어) 위험이 그 절감을 완전히 무력화**.

**결정 #2의 사후 판정**

> *"description은 한국어여야 한다"* — **부분 입증**
- ✅ 취소(시나리오 3): 영어 = Tool 미호출 + 중국어 응답 + raw JSON 노출 → *명백히 한국어 우세*
- △ 배달(시나리오 1): 둘 다 Tool 호출 성공. 단 한국어는 가나 드리프트, 영어는 가나 없음 → *finding끼리 상충, 영어가 부분적으로 우세*
- ⚠ 메커니즘 정정: 원안의 *"발화→reasoning→도구 선택 사이 한 단계 번역이 끼어 분기 정확도가 떨어짐"* 가설은 너무 약했음. 실측 메커니즘은 **언어 컨텍스트 자체의 시프트**(qwen2.5가 영어 description에 의해 중국어 모드로 전환됨)에 가깝다. *의미 거리 +1*이 아니라 *reasoning 언어 잠금의 해제*가 본질.

→ 결론: **결정 #2는 유지**. 단 근거 문장을 *"발화-도구 의미 거리"* 에서 *"reasoning 언어 컨텍스트 잠금"* 으로 재정의해야 더 정확.

**Raw 로그**
- [raw/stage1b-kr-vs-en.txt](raw/stage1b-kr-vs-en.txt) — KR/EN 통합 (각 시나리오 1·3 × 1회 = 4 호출)

#### 3. `OrderTools` 단일 클래스 — 현재 충분, 분리는 언제?

- 현재 한 클래스에 3개 메서드 묶음. 분리 기준 후보:
  - **(a) 조회 vs 변경** — `OrderQueryTools`(`getOrderDetail`, `getDeliveryStatus`) / `OrderCommandTools`(`cancelOrder`). 변경 Tool에만 트랜잭션·감사 로깅·소유자 검증 advisor를 거는 식으로 횡단 관심사 분리.
  - **(b) 도메인별** — `OrderTools` / `PaymentTools` / `DeliveryTools`. 도메인이 늘어나면 자연스러운 경계.
- 지금 묶어둔 이유: (1) Tool이 3개 → 한 화면에 들어옴, (2) 단일 도메인(주문), (3) 횡단 관심사(인증·트랜잭션)가 아직 코드에 진입 안 함.
- 분리 트리거: Tool ≥ 6개로 늘거나, Command 계열에 트랜잭션·소유자 검증·감사 advisor가 들어가는 시점.

## 2단계: cancelOrder Outcome 4가지 + 멱등 분기 제거 실험

**목표**: `cancelOrder`의 4가지 outcome 경로(`CANCELED` / `ALREADY_CANCELED` / `NOT_CANCELABLE` / `NOT_FOUND`)를 모두 LLM 자연어 호출로 트리거하고, 멱등성 분기를 의도적으로 제거했을 때 **시스템이 어떻게 망가지는지** 직접 관찰.

### §2-A. Outcome 4가지 자연어 응답 관찰

> *적용*: OrderTools 베이스 + BaedalPrompt 임시 룰(§3 변형 A의 [Tool 사용 규칙] 7항목). 멱등 분기는 *유지*된 정상 코드.

**측정 시도 — 4 라운드 총 12 호출**:

| 시도 | 발화 패턴 | 결과 |
|---|---|---|
| 1차 (5 호출) | "주문번호 YYYY-XXXX 취소해주세요" — 미션 원본 패턴 | 1/5만 cancelOrder 호출 (1236 NOT_CANCELABLE) |
| 2차 (4 호출) | "... 즉시 취소 처리해주세요. cancelOrder Tool 호출 부탁드립니다." — 강화 발화 | 1/4 호출 (1239 CANCELED) |
| 3차 (4 호출) | "주문 1238 취소요" / "9999-0000 취소 부탁" — 짧은 명령형 | 1/4 호출 (1238 ALREADY_CANCELED) |
| 4차 (2 호출) | "주문 0000-9999 취소요" / "abc-xyz 취소요" — NOT_FOUND 마지막 시도 | 0/2 호출 |

**Outcome 자연어 응답 (관찰 성공 3/4)**:

| Outcome | 트리거 발화 | LLM 자연어 응답 |
|---|---|---|
| ✅ **CANCELED** | `"주문번호 2024-1239 즉시 취소 처리해주세요. 확인 없이 바로 cancelOrder Tool을 호출해주세요."` | *"주문이 즉시 취소되었습니다. 배달이 시작되기 전이라 다행입니다. 추가로 도와드릴 일이 있으신가요?"* |
| ✅ **ALREADY_CANCELED** | `"주문 1238 취소요"` (사전 CANCELED, canceledReason="고객 요청") | *"주문이 이미 취소되었습니다. 다른 도움 필요하세요?"* |
| ✅ **NOT_CANCELABLE** | `"주문번호 2024-1236 취소해주세요"` (DELIVERED 상태) | *"주문이 이미 배달되어 완료된 상태라, 현재는 취소할 수 없습니다. 다른 방법을 도와드릴까요?"* |
| ❌ **NOT_FOUND** | `9999-0000`·`0000-9999`·`abc-xyz` × 4회 시도 모두 LLM이 cancelOrder 호출 회피 | (자연어 응답은 모두 *"주문번호를 다시 한번 확인해 주시면"* 식 되묻기 — Tool 결과 본 적 없음) |

> **NOT_FOUND outcome은 LLM이 4번 모두 호출 회피**. *형식 검증* 패턴으로 빠지거나 *"취소해드리겠습니다"* 식 거짓 약속 hallucinate. Tool 내부 코드의 NOT_FOUND 분기 message는 `"주문번호 " + orderId + " 를 찾을 수 없습니다."` — LLM이 이걸 받는 시나리오는 *코드 단위 테스트로만* 검증되고, 자연어 응답은 미관찰. 이는 미션 의도(LLM 응답 4건 모두)의 한계 — *Variant A 룰 + 강화 발화로도 NOT_FOUND 트리거 불가*가 이번 라운드의 부수 finding.

**추가 finding — 발화 미세 변화에 따른 cancelOrder 호출률의 변동성**

- 동일 의도 *"주문 1238 취소 부탁"* → ❌ 호출 회피
- 동일 의도 *"주문 1238 취소요"* → ✅ 호출 트리거 (3차 시도 [D''']에서 첫 ALREADY_CANCELED 관찰 가능했던 결정적 한 줄)

→ *"~ 부탁"* (정중한 부탁) 보다 *"~ 요"* (짧은 명령형) 가 cancelOrder description의 *"호출 시점: 고객이 '취소해주세요'…"* 매핑에 더 강하게 걸리는 것으로 추정. **발화 어미가 호출 결정에 영향** — 룰·description 어디에도 명시 안 했는데도. 도구 호출 정확도의 *모호성과 변동성*을 정량으로 보여주는 finding.

> raw: [raw/stage2-outcome-4.txt](raw/stage2-outcome-4.txt)

### §2-B. 멱등 분기 제거 — 시스템이 어떻게 망가지는지

> *적용*: BaedalPrompt 임시 룰 유지 + `OrderTools.cancelOrder`에서 `ALREADY_CANCELED` 분기(`status==CANCELED` 체크)를 **통째로 주석 처리**. `isCancelable()` 가드는 그대로 유지.

**코드 변경 (측정 중에만 적용)**:

```java
// 멱등 분기 — 이미 CANCELED 면 동일 응답으로 안전하게 끝낸다.
// if (order.status() == OrderStatus.CANCELED) {
//     String prev = order.canceledReason() != null ? order.canceledReason() : "사유 미기록";
//     return new CancelOrderResult(
//             orderId,
//             CancelOrderResult.Outcome.ALREADY_CANCELED,
//             "이미 취소된 주문입니다(이전 사유: " + prev + ").");
// }

if (!order.isCancelable()) {
    return new CancelOrderResult(orderId, NOT_CANCELABLE,
            "현재 상태(" + order.status() + ")에서는 취소가 불가합니다. 조리 시작 이후엔 취소가 제한됩니다.");
}
```

**측정 결과 (재시도 포함 총 6 호출, cancelOrder 실제 호출 = 3건)**:

| # | 발화 | cancelOrder 호출 | 응답 | 판정 |
|---|---|:-:|---|---|
| 1' | *"2024-1239 즉시 취소 처리..."* (1239=ACCEPTED, 첫 호출) | ✅ | *"주문이 즉시 취소되었습니다."* | ✅ CANCELED 정상 (예상대로) |
| 2' | *"방금 취소한 2024-1239 한 번 더 취소..."* | ❌ (LLM 회피) | *"이미 취소되었습니다"* (컨텍스트 기억 hallucination, Tool 결과 아님) | Tool 단 검증 못 함 |
| **3'** | *"주문번호 2024-1238 cancelOrder Tool 직접 호출해주세요..."* (사전 CANCELED) | ✅ | *"주문이 이미 취소되어 취소할 수 없습니다. **조리가 시작된 후에는 취소가 제한됩니다.**"* | ❌ **거짓 안내 — 멱등 분기 제거의 사고 재현** |

**[3']의 의미 — 핵심 finding**

`2024-1238`은 `status=CANCELED` + `canceledReason="고객 요청"` 인 *사전 취소된 주문*. 멱등 분기 제거 후 cancelOrder가 호출되자:

1. `status==CANCELED` 체크 분기 **부재** → 통과
2. `isCancelable()` = false (CANCELED는 CREATED·ACCEPTED가 아님) → **NOT_CANCELABLE 분기로 잘못 진입**
3. Tool message = `"현재 상태(CANCELED)에서는 취소가 불가합니다. 조리 시작 이후엔 취소가 제한됩니다."`
4. LLM이 이걸 풀어 안내 — *"주문이 이미 취소되어 취소할 수 없습니다. 조리가 시작된 후에는..."* 라고 **모순된 두 정보를 동시 출력** (*"이미 취소되어"* + *"조리가 시작된 후"*)
5. `canceledReason="고객 요청"` 정보는 **LLM에 도달 못 함** (Tool 내부 NOT_CANCELABLE 분기에서 가져오지 않음)

→ **사용자 시나리오**: 진짜 사고는 *"이 주문 조리 시작했나? 취소된 줄 알았는데 조리 중이라니?"* 하는 *주문 상태 자체에 대한 혼란*과 *환불 절차 우회 시도*.

**고객에게 줄 수 있는 오해 — 3가지**

1. **"이미 취소된 줄 알았는데 조리 시작?"** — 응답이 *"이미 취소"* 와 *"조리 시작"* 을 동시에 박아 고객이 *주문이 실제로 어느 상태인지* 알 수 없음. 다른 메뉴 추가 주문 또는 매장에 직접 전화하려는 행동으로 이어질 수 있음.
2. **"환불은 받을 수 있나?"** — *"취소가 제한됩니다"* 라는 NOT_CANCELABLE 톤 메시지를 받으면 환불 절차 자체가 막힌 것으로 오해 → 사실은 이미 취소돼 환불 절차에 들어가 있음에도 *다른 환불 채널을 우회*로 찾음(카드사 차지백 등 — 분쟁 비용 폭증).
3. **"내가 진짜 취소한 게 맞나?"** — 첫 번째 취소 후 의심 들어 한 번 더 확인 요청한 케이스(2' 시나리오)에서 LLM이 *"이미 취소되었습니다"* (Tool 결과가 아닌 컨텍스트 hallucination)라고 답하면, 고객은 *시스템이 정말로 취소를 처리했는지* 확신 못 함 — 신뢰 하락.

**프로덕션 장애 — 3가지 (실제 OrderService라면)**

1. **결제 이중 취소** — 멱등 분기 없이 `order.cancel()`이 두 번 호출될 수 있는 코드 패스라면, PG 모듈에 cancel 호출이 두 번 전송되어 (a) 일부 PG는 *"이미 취소된 거래"* 4xx 응답 → 사장님 정산 데이터 불일치 (b) 일부 PG(특히 멱등성 헤더 없는 구식)는 *마이너스 잔액* 처리.
2. **사장님 / 라이더 알림 중복** — 취소 알림이 두 번 발송되면 사장님이 *"이미 알림 받았는데 또 왔다"* → 사장님 콜센터 컴플레인. 라이더는 *재배차 vs 취소* 사이에서 혼란.
3. **포인트·쿠폰 이중 환급** — 사용한 포인트/쿠폰 자동 복귀 로직이 cancelOrder에 묶여 있다면 두 번 호출 시 포인트 두 배 환급 → 리워드 시스템 부정.
4. (보너스) **감사 로그 정합성 깨짐** — *"NOT_CANCELABLE"* 이 1238처럼 *이미 취소된* 주문에 찍히면 CS 후속 처리 시 *"왜 이 주문이 NOT_CANCELABLE이 됐지?"* 추적 불가능. 진짜 사고 발생 시 책임 소재 추적 비용 폭증.

> raw: [raw/stage2-idempotency-removed.txt](raw/stage2-idempotency-removed.txt)

### §2-C. 설계 결정 답변

#### 1. 왜 Outcome 4개인가 — UNKNOWN/FAILED를 안 넣은 이유

| Outcome | 처리 방향 (호출자 측) |
|---|---|
| `CANCELED` | "취소되었습니다" 안내 + 환불·알림 후속 |
| `ALREADY_CANCELED` | 멱등 응답 — 동일 안내, *별도 처리 없음* |
| `NOT_CANCELABLE` | 정책 안내 ("조리 시작 후 불가") + 보상·예외 처리 가이드 |
| `NOT_FOUND` | "주문번호 확인" 되묻기 + 본인 확인 트리거 |

4개는 **서로 다른 후속 처리 흐름**을 가짐. *UNKNOWN/FAILED* 를 넣지 않은 이유:

- **UNKNOWN** — *"무엇을 안내해야 하는지 정해지지 않은 상태"* 는 outcome 분기로 다룰 게 아니라 **명시적 오류(예외)** 로 던져 *알람·재시도 로직*이 잡게 해야 함. UI/LLM 응답에 *"잘 모르겠어요"* 가 흘러가면 고객 신뢰 더 깎임 + 운영자가 *언제 UNKNOWN이 발생했는지* 추적도 어려워짐.
- **FAILED** — 실패 *원인*(DB 락 / 외부 PG 응답 지연 / 권한 부족 등)이 달라 각각의 후속 처리도 다름. 단일 FAILED로 묶으면 다시 분기해야 함 — outcome enum의 *후속 처리 단위* 원칙에 어긋남. *예외*로 던지고 advisor·circuit breaker가 잡게 두는 게 낫다.

#### 2. 추가 가능한 Outcome 2개 (배달 운영 시나리오)

- **`REQUIRES_AGENT`** — *자동 취소 불가, 상담사 이관 필요* 케이스.
  - 시나리오: (a) 고가 주문(예: 30만원 이상) 자동 취소 시 사장님 손실 가능성 → 사람 검토 필수. (b) 동일 고객이 5분 내 3회 이상 cancelOrder 시도 → *어뷰징 의심* 신호로 상담사 이관. (c) 사장님 *"이 주문은 직접 통화 후 처리해주세요"* 플래그 설정.
  - 호출자 측 처리: LLM 응답에 *"전담 상담사로 연결해 드리겠습니다"* 안내 + 상담 큐 enqueue.
- **`COOLING_OFF`** — *결제 후 일정 시간 내(예: 5분)에만 즉시 취소, 이후는 cooling-off 대기 후 처리*.
  - 시나리오: 사장님 수락 직후 시간(ACCEPTED 직후 0-5분)엔 매장이 막 조리 준비를 시작하므로 *식자재 손실 0* 보장 못 함. 자동 즉시 취소 대신 *5분 hold-and-confirm* 워크플로.
  - 호출자 측 처리: LLM 응답 *"5분 안에 매장 응답이 없으면 자동 취소됩니다"* + 비동기 콜백 등록.

#### 3. 멱등성의 세 가지 수준 — cancelOrder가 "같은 응답 재전달"인 이유

세 가지 수준:

1. **에러** — 두 번째 호출에 예외/4xx 응답.
2. **무시 (silent OK)** — 두 번째 호출도 성공으로 응답하되 *내부 상태는 변경 없음*.
3. **같은 응답 재전달** — 두 번째 호출에 첫 호출과 *동일한 응답 페이로드* 반환 (멱등성 원본 원칙).

`cancelOrder`는 **(3) 같은 응답 재전달** — `ALREADY_CANCELED` outcome으로 *왜 이미 취소됐는지(canceledReason)* 까지 포함. 이유:

- **분산 시스템에서 재시도가 흔하다** — 클라이언트 timeout / 네트워크 끊김 / LLM tool calling retry 모두 같은 cancelOrder를 다시 호출할 수 있음. 에러로 응답하면 *재시도 → 또 에러* 의 루프가 도는 운영 사고.
- **고객 발화 패턴에 *재확인이 흔하다*** — *"진짜 취소됐어요? 한 번 더 취소해주세요"* 같은 발화는 일상. 에러 응답하면 LLM이 *그것 자체를 사고처럼 풀어 안내* 하게 됨.
- **감사 로그·CS 응대 비용 절감** — `ALREADY_CANCELED` 응답은 *"이미 취소 처리됐고 사유는 X였습니다"* 라는 *완결된 정보*를 한 번에 줌. CS 응대가 단순화.

**"에러"가 더 적절한 예** — *상태 전이 자체가 도메인 불변식(invariant) 위반인 경우*. 예: **결제되지 않은 주문에 결제취소(refund) 요청** — 결제가 안 됐는데 환불이라는 개념이 성립 안 함 → 호출자가 *논리적 오류*를 가지고 있다는 명시적 신호. silent OK나 같은 응답 재전달로 가면 *"환불됐다고 알려졌는데 실제론 결제도 안 됐던"* 더 큰 정합성 사고로 번짐. 이런 invariant 위반은 *예외*로 강하게 막아 호출자 측 버그를 노출시키는 게 옳다.

### §2 코드 최종 상태

- `OrderTools.cancelOrder` — 멱등 분기 **복원** (CANCELED 체크 → ALREADY_CANCELED 응답).
- `BaedalPrompt.java` — `[Tool 사용 규칙]` 제거 후 베이스로 복원 (§2 측정 한정 임시 룰).
- §3 코드 복원 상태와 일치 → 본 PR의 *최종 코드 상태* 는 *§1단계 완료 시점의 베이스라인* 동일.

### Raw 로그

> [raw/stage2-outcome-4.txt](raw/stage2-outcome-4.txt) — Outcome 4가지 자연어 응답 (1·2·3·4차 시도, 4 라운드 12 호출)
> [raw/stage2-idempotency-removed.txt](raw/stage2-idempotency-removed.txt) — 멱등 분기 제거판 (1차 3건 + 재시도 3건, 핵심 [3'] 1238 사고 재현)
## 3단계: Tool description 실험 — 미션(getDeliveryStatus A/B/C) + 형태·위계 확장

**목표**: 미션 §3 원본의 "description이 LLM에게 보여주는 유일한 API 문서임을 정량적으로 체감"을 그대로 측정한다(아래 *미션 §3 본체*). 거기에 *형태 1축 비교만으로는 좁다*는 판단으로, description *형태* 4종과 description ↔ BaedalPrompt(시스템 프롬프트) *위계* 2종을 **확장 실험**으로 덧붙였다. §1단계 시나리오 3·4에서 본 cancelOrder 미호출/hallucination이 description 변형으로 해소되는지가 확장의 핵심 질문.

> **이 단계 구성**: ① **미션 §3 본체** — `getDeliveryStatus` 한 description을 A/B/C로 바꿔 측정(미션 자체 수행) → ② **확장 실험** — 형태 4종·위계 충돌 2종(본인 추가 검증) → ③ **종합·Finding** — 미션+확장 통합.

---

## 미션 §3 본체 — `getDeliveryStatus` description 3버전 (A/B/C)

> 미션은 **`getDeliveryStatus` 한 Tool의 description만** A(기준)/B(빈약)/C(오해 유발)로 바꿔, 같은 질문 `"주문번호 2024-1234 배달 어디쯤이에요?"` 를 버전당 5회 호출해 *호출 여부*를 본다. 아래 *정량 비교 표* → *변형 E·F 상세* → *미션 설계 결정 답변* 순.

### §3-b 정량 비교 — A 기준 / B 빈약 / C 오해 유발 (미션 §3 핵심 산출물)

> 미션 §3가 요구한 *동일 질문 × 3 description 버전* 정량 비교. 측정 쿼리는 **`"주문번호 2024-1234 배달 어디쯤이에요?"`** 로 고정(`2024-1234` = DELIVERING, 정답은 `getDeliveryStatus` 호출 → *"역삼역 사거리"* 안내). 세 버전 모두 `getDeliveryStatus` 한 Tool의 description만 바꾸고 나머지는 베이스 유지.

| 버전 | description | `getDeliveryStatus` 호출 | *"역삼역 사거리"* 정답 | 파손·오분기 |
|---|---|:--:|:--:|---|
| **A 기준** (정상 long 4요소) | 한국어 4요소(무엇/언제/입력/실패) | 2/3 (67%)† | 2/3 | raw JSON 1/3 (R3.1) |
| **B 빈약** (변형 E) | `"배달 정보 조회"` 한 줄 | 3/5 (60%) | 3/5 | **raw JSON 2/5** |
| **C 오해 유발** (변형 F) | `"주문번호 조회용. 메뉴와 결제 금액만 반환한다."` | **0/5** | **0/5** | `getOrderDetail` 3/5 오분기 + *"준비 중"* 거짓 합성 |

> † **A 기준은 전용 5회 측정이 아니라 §1단계 시나리오 1(동일 쿼리, 3회 = R1.1·R2.1·R3.1) 데이터를 그대로 끌어옴.** B·C와 측정 횟수(3 vs 5)·세션이 달라 *완전한 클린 A/B/C* 는 아님 — 단 동일 쿼리·동일 정답 기준이라 *방향성 비교*로는 충분. 전용 5회 재측정은 후속 과제로 남김.

**미션 체크리스트 판정**:

- ✅ **버전 B — "로그가 안 찍히거나 줄어드는가?"** : 호출률 자체는 A(67%) ≈ B(60%)로 *극적으로 줄진 않음*. 그러나 **raw JSON 파손이 1/3 → 2/5로 증가** — 빈약 description은 *호출 횟수보다 tool protocol 안정성*을 먼저 깎는다는 게 실측 결론.
- ✅ **버전 C — "`getOrderDetail`을 대신 부르거나 엉뚱한 답?"** : **둘 다 발생**. `getDeliveryStatus` 0/5 완전 회피 + `getOrderDetail` 3/5 오분기 + 잘못된 Tool의 `storeName`으로 *"준비 중"* 거짓 응답 합성. 미션이 예측한 실패 모드를 정확히 재현.

> 각 버전의 전체 응답·로그·발췌는 아래 변형 E(빈약)·F(오해 유발) 절에서 상세히 다룬다. A 기준 원문은 §1단계 *검증 — 시나리오 5종 × 3 라운드* 의 시나리오 1 행 참조.

---

### §3-b 변형 E — 빈약 (미션 §3 원본의 "빈약 description")

> *BaedalPrompt 베이스. **`getDeliveryStatus` 한 Tool만** description을 1줄로. 다른 2개는 베이스 long 유지.*

**description**:

```java
@Tool(description = "배달 정보 조회")
public DeliveryStatusView getDeliveryStatus(...) { ... }
```

**측정 쿼리**: `"주문번호 2024-1234 배달 어디쯤이에요?"` × 5회 (베이스라인 §1단계 시나리오 1과 동일).

**결과**:

| 지표 | 결과 | 비고 |
|---|---:|---|
| `getDeliveryStatus` 실제 호출 | 3/5 | |
| 응답에 *"역삼역 사거리"* 포함 | 3/5 (60%) | Tool 호출된 케이스만 |
| **응답 본문에 raw function-call JSON 노출** | **2/5** | 확장 §3-a 변형 C(영어)와 동일 파손 |

raw JSON 노출 예시 (시나리오 1):

```text
먼저 주문 상태를 확인해 보겠습니다.

ONGL
{"name": "getDeliveryStatus", "arguments": {"orderId": "2024-1234"}}
</tool_call>
```

**Finding**: 빈약 description에서도 확장 §3-a 변형 C(영어)와 같은 *tool protocol 우회 + raw JSON 모드*가 재현. **description의 신호가 약할수록 모델이 tool calling 메커니즘 자체를 우회하는 경향**.

> raw: [raw/stage3b-variant-E-sparse.txt](raw/stage3b-variant-E-sparse.txt)

---

### §3-b 변형 F — 오해 유발 (미션 §3 원본의 "오해 유발 description")

> *BaedalPrompt 베이스. **`getDeliveryStatus` 한 Tool만** description을 실제 동작과 어긋난 문장으로.*

**description**:

```java
@Tool(description = "주문번호 조회용. 메뉴와 결제 금액만 반환한다.")
public DeliveryStatusView getDeliveryStatus(...) { ... }  // 실제로는 status+riderLocation 반환
```

**측정 쿼리**: `"주문번호 2024-1234 배달 어디쯤이에요?"` × 5회.

**결과**:

| 지표 | 결과 |
|---|---:|
| `getDeliveryStatus` 호출 | **0/5** |
| `getOrderDetail` 호출 (잘못된 Tool 분기) | 3/5 |
| 응답에 *"역삼역 사거리"* 포함 | **0/5** |
| **추가 finding** | 응답에 *"교촌치킨 강남점에서 **준비 중**"* (실제 status=DELIVERING)이라는 storeName 데이터로 *그럴듯한 거짓 응답* 생성 |

응답 발췌 ([D.2]):

> 주문이 현재 배달 중입니다. 교촌치킨 강남점에서 **준비되어 있으며**, 예상 도착 시간은 23시 12분입니다.

**Finding**: 미션 §3가 의도한 *"description이 실제 동작과 어긋날 때 LLM이 그 거짓 description을 따라간다"* 가 그대로 재현 — `getDeliveryStatus`를 *"메뉴와 결제 금액만 반환"* 으로 표기하니 모델이 "그럼 배달 위치는 다른 Tool이겠지"라고 판단해 `getOrderDetail`로 분기. 게다가 잘못된 Tool에서 받은 `storeName` 데이터로 *그럴듯한 거짓 응답*까지 합성 — **단일 description 오류가 hallucination을 유발하는 명확한 경로**.

> raw: [raw/stage3b-variant-F-misleading.txt](raw/stage3b-variant-F-misleading.txt)

---

### §3-b 미션 §3 설계 결정 답변

#### 1. 변형 F(오해 유발)에서 LLM이 어떤 행동을 했는가?

- 0/5 `getDeliveryStatus` 미호출.
- 대신 3/5 `getOrderDetail`로 분기 — description의 *"이 Tool은 메뉴와 결제 금액만"* 이라는 거짓말을 그대로 믿고, "그럼 배달 위치는 메뉴 조회용이 아니니 다른 Tool이겠지"라고 판단.
- 응답엔 **`storeName` 데이터(*"교촌치킨 강남점"*)를 가져다가 "준비되어 있으며"라고 자연어로 그럴듯하게 합성** — 실제 status는 DELIVERING이지만 모델이 본 데이터만으로 *준비 중*이라 거짓 응답.
- 즉 **Hallucination** + **잘못된 Tool 분기** + **부수 데이터 재해석** 3가지가 동시에 발생.

#### 2. description 작성 시 반드시 포함해야 할 4가지 — 본인 실험 기반 *중요도 순*

강의 자료의 4요소(무엇/언제/입력/실패)를 **이번 실험 결과 기준으로 재배열**:

1. **언제 호출하는가 — 발화 패턴 매핑 (가장 중요)** : 변형 D(예시 포함, 확장 §3-a)에서도 *문장 수준 예시*보다 *발화 의미 매핑*이 약한 게 보였지만, 시나리오 3·4가 모두 *언제 호출* 의 신호가 약해 실패함. 다른 Tool과의 *경계*(예: "메뉴는 이쪽, 라이더 위치는 저쪽")까지 명시해야 분기 정확도가 올라감 — 변형 F가 그 경계를 거짓말로 박았더니 즉시 망가진 게 증거.
2. **실패 시 반환값 — null vs outcome enum** : 모델이 *"호출 결과가 없거나 부적합할 때 무엇을 받는지"* 알아야 *호출 후 응답*을 정직하게 짤 수 있음. 변형 A(확장 §3-a)의 "Tool 결과의 status·outcome을 그대로 반영하라"가 시나리오 3 hallucination을 일부 줄인 이유.
3. **무엇을 하는가 — 동작의 정확한 묘사** : 변형 F가 보여줬듯 이게 거짓이면 즉시 *그 거짓을 따라가는 행동*이 나옴. 단, 1·2가 갖춰져 있으면 *무엇*이 짧아도 작동(변형 B(확장 §3-a)는 짧음 자체보다 *언제*가 빠져서 망함).
4. **입력 형식 — orderId의 패턴** : 가장 영향 작음. `YYYY-XXXX` 명시 없어도 모델은 발화에서 그대로 추출함. 다만 ToolParam에 있으면 *잘못된 형식 발화*("주문 1234")를 정규화하는 데 도움.

> 강의 자료의 4요소를 그대로 두는 것엔 문제가 없다 — 단, **시간을 쓸 가치 순서**가 *발화 매핑 > 실패 반환 > 동작 묘사 > 입력 형식*.

#### 3. description이 "오래된 주석"처럼 실제 Tool 동작과 어긋나게 되는 상황은 어떻게 막을 것인가

변형 F는 *description이 거짓말일 때 시스템이 어떻게 망가지는지* 의 실측 증거 — *오래된 주석* 시나리오가 LLM 시스템에서는 *조용한 운영 사고*가 된다(예외도 안 뜨고 사용자에게 그럴듯한 거짓 응답이 나감). 방어 전략을 3축으로:

- **계약 테스트 (Contract Test) — 가장 강력** : `OrderToolsDescriptionContractTest` 같이 *description 자체*를 정규화 텍스트로 추출해, "이 Tool description은 (a) 동작 요약 / (b) status 또는 outcome 명세 / (c) 입력 형식을 포함해야 한다" 같은 골든 마스터/스키마 어서션을 단위 테스트로 둠. Tool 본문이 바뀌면(예: 반환 필드 추가/삭제) description도 같이 바뀌어야 한다는 *체크포인트*가 됨. 더 나아가 *description ↔ 반환 타입 필드명 일치* 도 자동 검증 가능(jsonschema 기반).
- **PR 리뷰 체크리스트** : `@Tool` 또는 반환 DTO를 수정한 PR은 description도 같이 변경됐는지 리뷰어가 확인. CODEOWNERS로 OrderTools류 변경에는 도메인 시니어 리뷰 강제.
- **운영 관측 (가장 사후적이지만 필수)** : Tool 호출 분포 추적 — *특정 시점부터 어떤 Tool 호출률이 급격히 변하면* description 오타·동작 변경 신호. 본 라운드의 `PerformanceLoggingAdvisor`에 Tool 호출명·outcome 차원을 추가하면 메트릭으로 잡힘. 추가로 *raw function-call JSON이 응답 본문에 등장*하는 패턴(확장 §3-a 변형 C·미션 변형 E에서 본 시스템 파손)도 알람 후보.

> 즉, "오래된 주석" 방어의 절반은 **계약 테스트로 사전 차단**, 나머지 절반은 **운영 메트릭으로 사후 감지**. PR 리뷰는 그 사이의 마지막 사람의 손.

---

## 확장 실험 (미션 외 — description 형태·위계 추가 검증)

> 미션은 description *형태* 1축(빈약/오해)만 본다. 여기서는 **형태 4종**(§3-a: 동맹/짧음/영어/예시)과 **description ↔ 시스템 프롬프트 위계 2종**(§3-c: 충돌)을 더해 *무엇이 cancelOrder 호출률을 끌어올리는지* 를 넓게 측정. cancelOrder 미호출/hallucination(§1단계 시나리오 3·4)이 description 변형으로 해소되는지가 핵심 질문.

### 확장 실험 설계

총 **형태 4종 + 충돌 2종 = 6 측정** (총 36 호출). 각 변형마다 `OrderTools.java`(또는 `BaedalPrompt.java`) 한 군데만 바꾸고 나머지는 베이스로 유지해 *원인 변수를 격리*. 각 변형마다 raw 파일 별도 저장(`raw/stage3*.txt`).

| 그룹 | 변형 | 무엇이 바뀌나 | 측정 쿼리 | 호출수 |
|---|---|---|---|---|
| **§3-a 형태 비교** | A 동맹 | BaedalPrompt에 `[Tool 사용 규칙]` 추가 (description은 베이스) | 시나리오 3·4(취소) | 6 |
| | B 짧음 | OrderTools 3개 description 모두 1줄 핵심으로 | 시나리오 3·4 | 6 |
| | C 영어 long | OrderTools 3개 description 모두 영어 4요소 long | 시나리오 3·4 | 6 |
| | D 예시 포함 | OrderTools 3개 description = 한국어 long + few-shot 예시 3건씩 | 시나리오 3·4 | 6 |
| **§3-c 충돌** | 충돌 1 책임 분기 | BaedalPrompt "2단계 호출 필수" ↔ cancelOrder description "단독 호출하라" (정반대) | 시나리오 3·4 | 6 |
| | 충돌 2 본문 톤 | BaedalPrompt "status는 자연어로 풀어" ↔ getOrderDetail description "enum 그대로" (정반대) | "1234·1236 상태 뭐예요?" | 6 |

> 베이스라인은 §1단계 시나리오 3·4 결과(각 1회). 이번 확장 변형들은 그 위에 효과를 측정.

---

### §3-a 변형 A — 동맹 (BaedalPrompt에 [Tool 사용 규칙] 추가)

> *description은 베이스(한국어 long 4요소) 유지. BaedalPrompt만 보강.*

**BaedalPrompt 추가 라인** (이 라운드 한정):

```text
[Tool 사용 규칙]
- 발화에 주문번호(YYYY-XXXX 형식, 예: 2024-1234)가 포함되면 반드시 주문 관련 Tool을 호출합니다. 추측·짐작으로 답하지 않습니다.
- "취소", "캔슬", "물러주세요" 등 취소 의도 키워드가 발화에 명시되면 반드시 cancelOrder Tool을 호출합니다. getOrderDetail로 상태만 확인하고 갈음하지 않습니다 — 정책 분기(NOT_CANCELABLE / ALREADY_CANCELED 등)는 cancelOrder의 outcome으로 응답해야 합니다.
- "배달 상태·위치·도착시간"을 묻는 발화는 getDeliveryStatus 를 호출합니다.
- "메뉴·금액·주문 상태"를 묻는 발화는 getOrderDetail 을 호출합니다.
- 일반 인사("안녕하세요", "고맙습니다")에는 Tool을 호출하지 않습니다.
- 정책 질문("환불 가능해요?", "취소 정책이 뭐예요?")처럼 특정 주문번호 없이 일반 정책을 묻는 발화에는 Tool 없이 정책 텍스트로만 답합니다.
- Tool 결과의 status·outcome 필드는 그대로 본문에 반영합니다. 임의로 다르게 해석·재서술하지 않습니다 (예: status=CREATED 인 결과를 "이미 취소되었습니다" 라고 답해서는 안 됩니다).
```

**결과** (시나리오 3·4 × 3회 = 6 호출):

| Tool 호출 시퀀스 | 분포 |
|---|---:|
| `cancelOrder` 직접 호출 → 정확한 outcome 응답 | **2/6** (33%) |
| `getOrderDetail`만 호출 → "취소하겠습니다" 약속하나 cancelOrder 호출 안 함 (*promise hallucination*) | 1/6 |
| **Tool 0회 호출** — "취소되도록 요청하였습니다" 거짓 약속만 | 3/6 |

베이스라인(§1단계) 시나리오 3·4 0/2 → 변형 A 2/6 (33%) — **유일하게 cancelOrder 호출률을 끌어올린 변형**. 그러나 여전히 빈약. 룰을 "읽기는" 했으나 *부분 이행*에 그침(시나리오 3.2가 그 사례 — getOrderDetail로 status=CREATED 확인했으면서 cancelOrder는 안 부르고 "취소하겠습니다"로 약속).

> raw: [raw/stage3a-form-variants.txt](raw/stage3a-form-variants.txt) — 통합본 `[A 동맹]` 섹션

---

### §3-a 변형 B — 짧음 (1줄 description)

> *BaedalPrompt 베이스. OrderTools 3개 description을 1줄 핵심만으로 축약.*

**description (3개 모두)**:

```java
@Tool(description = "주문 상세(매장·메뉴·금액·상태) 조회. 고객이 메뉴/금액/상태를 물을 때 호출.")
@Tool(description = "배달 상태·라이더 위치 조회. 고객이 배달 진행 상황을 물을 때 호출.")
@Tool(description = "주문 취소. 고객이 취소를 요청할 때 호출. 4분기 outcome: CANCELED/ALREADY_CANCELED/NOT_CANCELABLE/NOT_FOUND.")
```

**결과**:

| 지표 | 결과 |
|---|---:|
| Tool 호출 총 횟수 | **0/6** |
| 6 응답 모두 *"처리하겠습니다", "확인하겠습니다"* 식 promise hallucination | 6/6 |
| 입력 토큰 (Tool 미호출 단일 LLM 라운드) | ~1,460 (베이스 4,000+ 대비 약 65% 절감) |

**Finding**: 짧은 description은 토큰을 줄이지만 *언제 호출해야 하는지* 의 신호가 약해서 모델이 **자연어로 답하는 게 더 편한 길**이 됨. 결과적으로 Tool 호출 자체를 안 함.

> raw: [raw/stage3a-form-variants.txt](raw/stage3a-form-variants.txt) — 통합본 `[B 짧음]` 섹션

---

### §3-a 변형 C — 영어 long

> *BaedalPrompt 베이스. OrderTools 3개 description을 영어 4요소 long으로.*

**description 발췌** (`cancelOrder`):

```java
@Tool(description = """
        Cancel an order.
        Call when the customer explicitly requests cancellation (e.g., "취소해주세요", "주문 취소").
        Cancelable states: CREATED (right after order), ACCEPTED (right after store accepts).
        Not cancelable: COOKING / DELIVERING / DELIVERED.
        Idempotent — calling on an already-canceled order returns outcome=ALREADY_CANCELED (not an exception).
        Result mapping (CancelOrderResult.outcome):
          CANCELED          → "주문이 취소되었습니다"
          ALREADY_CANCELED  → "이미 취소된 주문입니다"
          ...
        """)
```

**결과**:

| 지표 | 결과 |
|---|---:|
| 실제 Spring AI 파이프라인 통과한 Tool 호출 | 1/6 (`getOrderDetail` 만) |
| **응답 본문에 raw function-call JSON이 그대로 노출 (시스템 파손)** | **3/6** |
| 일반 hallucination | 2/6 |

raw JSON 노출 예시:

```text
{"name": "cancelOrder", "arguments": {"orderId": "2024-1236", "reason": "고객 요청"}}
```

**Finding**: qwen2.5가 영어 description을 받으면 **Spring AI tool calling protocol을 우회하고 응답 텍스트에 직접 JSON을 박는 모드**로 들어감 — Tool 시스템 자체가 파손. 발화(한국어)와 도구 설명(영어) 사이 의미 거리가 분기 정확도를 망가뜨리는 것으로 추정.

> raw: [raw/stage3a-form-variants.txt](raw/stage3a-form-variants.txt) — 통합본 `[C 영어 long]` 섹션

---

### §3-a 변형 D — 예시 포함 (한국어 long + few-shot 예시)

> *BaedalPrompt 베이스. OrderTools description = 한국어 long(4요소) + 각 Tool에 발화→호출 매핑 예시 3건씩.*

**description 발췌** (`cancelOrder`):

```java
@Tool(description = """
        주문을 취소합니다.
        호출 시점: 고객이 "취소해주세요", "주문 취소하고 싶어요" 등 명시적으로 취소를 요청할 때.
        취소 가능 조건: 상태가 CREATED(주문 직후) 또는 ACCEPTED(사장님 수락 직후)일 때만 가능합니다.
        ...
        예시:
          "1234 취소해주세요"                       → cancelOrder(orderId="2024-1234", reason="고객 요청")
          "방금 시킨 1235 그냥 취소해주세요"         → cancelOrder(orderId="2024-1235", reason="고객 요청")
          "1234 주소 잘못 적었어요, 캔슬해주세요"    → cancelOrder(orderId="2024-1234", reason="주소 오기재")
        """)
```

> 측정 쿼리 "**방금 시킨 2024-1235 그냥 취소해주세요**"가 description 예시와 거의 동일한 표현.

**결과**:

| 지표 | 결과 |
|---|---:|
| Tool 호출 총 횟수 | **0/6** |
| 6 응답 모두 BaedalPrompt [응답 포맷] 1)2)3) 구조를 *충실히* 따르며 promise hallucination | 6/6 |

**Finding**: description에 *측정 쿼리와 거의 동일한 매핑 예시*를 박았음에도 Tool 호출이 0회. **few-shot 예시도 Tool 호출 결정에 영향을 못 미친다**. 한편 모델은 BaedalPrompt의 응답 포맷("1) 핵심답변 2) 추가 질문 3) 다음 액션")을 그대로 따른 게 인상적 — **응답 *형식* 지시는 잘 따르지만 *행동(도구 호출)* 지시는 잘 안 따르는** 비대칭.

> raw: [raw/stage3a-form-variants.txt](raw/stage3a-form-variants.txt) — 통합본 `[D 예시 포함]` 섹션

---

### §3-c 충돌 1 — 책임 분기 (description vs BaedalPrompt 정반대)

> *둘 다 반대 지시를 동시에 박고, 모델이 어느 쪽 따르는지 본다.*

**BaedalPrompt 추가 라인**:

```text
[Tool 사용 규칙]
- 취소 요청 시에는 반드시 먼저 getOrderDetail 로 주문 상태를 확인한 뒤에 cancelOrder 를 호출합니다. 두 단계 호출이 필수입니다.
```

**cancelOrder description 추가 라인**:

```text
[중요] 호출 전 getOrderDetail 등 다른 Tool로 상태를 미리 확인하지 마세요. 취소 가능 여부 판정은 이 Tool의 책임입니다 — 곧바로 단독 호출하세요.
```

**판정 지표**: Tool 호출 시퀀스 — `getOrderDetail → cancelOrder`(BaedalPrompt 승) / `cancelOrder` 단독(description 승) / 0회 혹은 `getOrderDetail` 단독(둘 다 못 따름).

**결과** (시나리오 3·4 × 3 = 6):

| # | Tool 호출 시퀀스 | 판정 | 응답 발췌 |
|---|---|---|---|
| 3.1 | `getOrderDetail` → `cancelOrder` | ✅ **BaedalPrompt 승** | "주문이 취소되었습니다" ✅ |
| 3.2 | `getOrderDetail` (단독) | △ 둘 다 부분 | "이미 취소되었습니다" |
| 3.3 | (0 Tool) | ❌ 회피 | "주문 상세 정보를 확인할 수 있을까요?" |
| 4.1 | `getOrderDetail` (단독) | △ 둘 다 부분 | "이미 배달 완료. 취소 불가" |
| 4.2 | (0 Tool) | ❌ 회피 | 되묻기 |
| 4.3 | (0 Tool) | ❌ 회피 | 되묻기 |

**Finding**: 두 지시가 *상호 배타적*(2단계 호출 vs 1단계 단독)이라 모델이 **둘 다 일부 무시 + 안전한 길로 회피** 하는 경향. cancelOrder 자체를 피함(0 Tool 3/6). 명확한 우승은 1/6에서만(`3.1`에서 BaedalPrompt 승).

> raw: [raw/stage3c-conflicts.txt](raw/stage3c-conflicts.txt) — 통합본 `[충돌 1 책임 분기]` 섹션

---

### §3-c 충돌 2 — 본문 톤 (description vs BaedalPrompt 정반대)

**BaedalPrompt 추가 라인**:

```text
[Tool 사용 규칙]
- Tool 결과의 status 값(예: CREATED, COOKING, DELIVERED 등)은 고객이 이해하기 쉽도록 반드시 자연어로 부드럽게 풀어서 안내합니다. 예: "DELIVERED" → "이미 배달이 완료되어", "COOKING" → "조리가 진행 중이에요". 영문 상태 enum 값을 그대로 응답에 노출하지 않습니다.
```

**getOrderDetail description 추가 라인**:

```text
[중요] 응답에는 결과의 status 값을 영문 enum 그대로(예: CREATED, COOKING, DELIVERED) 정확히 안내하세요. 자연어로 풀어쓰거나 재해석하지 마세요 — 정확한 시스템 라벨을 유지해야 합니다.
```

**판정 지표**: 응답에 영문 enum 등장(description 승) / 자연어 풀어쓰기(BaedalPrompt 승) / 둘 다 등장(합성).

**측정 쿼리**: `"2024-1234 지금 상태가 뭐예요?"` × 3 (DELIVERING) + `"2024-1236 지금 상태가 뭐예요?"` × 3 (DELIVERED) = 6 호출.

**결과**:

| # | 응답 발췌 | 영문 enum | 자연어 풀어쓰기 |
|---|---|:-:|:-:|
| D.1 | *"상태는 **"DELIVERING"** 입니다. 현재 **배달이 진행 중**에 있습니다."* | ✅ | ✅ |
| D.2 | *"현재 **"DELIVERING"** 상태입니다. 즉, **배달이 진행 중**인 것으로"* | ✅ | ✅ |
| D.3 | *"상태는 **"DELIVERING"** 입니다. 현재 **배달이 진행 중**이라고 안내드립니다."* | ✅ | ✅ |
| D.4 | *"**이미 배달 완료되어** 상태는 **"DELIVERED"** 입니다."* | ✅ | ✅ |
| D.5 | *"**이미 배달 완료되어** 상태는 **"DELIVERED"** 입니다."* | ✅ | ✅ |
| D.6 | *"상태는 **"DELIVERED"** 입니다. **이미 배달이 완료되어** 음식을 받으셨습니다."* | ✅ | ✅ |

**Finding (충돌 1과 정반대 패턴)**: 두 지시가 *동시 만족 가능* (literal + paraphrase를 같은 문장에 박을 수 있음) → 모델이 **합성** 으로 처리(6/6 둘 다 등장). 충돌 1의 *회피* 와는 다른 적응 전략.

> raw: [raw/stage3c-conflicts.txt](raw/stage3c-conflicts.txt) — 통합본 `[충돌 2 본문 톤]` 섹션

---

## 종합·Finding (미션 + 확장 통합)

### §3 종합 — 9 변형 한눈에 (미션 E·F + 확장 6)

| 변형 | cancelOrder/Tool 정확도 | 부가 finding |
|---|:---:|---|
| Baseline (§1단계) | 0/2 cancelOrder | hallucination 1건, 일본어 가나 |
| **A 동맹** (BaedalPrompt+룰) | **2/6** (33%) | *유일하게 끌어올린 변형*. 그러나 여전히 빈약 |
| B 짧음 | 0/6 | Tool 호출 자체 안 함, 토큰만 65% 절감 |
| C 영어 long | 1/6 + 3/6 raw JSON | tool protocol 우회 → 시스템 파손 |
| D 예시 포함 | 0/6 | 매핑 예시도 효과 0, 응답 *형식*만 잘 따름 |
| E 빈약 (미션 B) | 3/5 + 2/5 raw JSON | 부분 호출 + 파손 혼재 |
| F 오해 유발 (미션 C) | 0/5 → 3/5 잘못된 Tool | description 거짓말 따라가 hallucinate |
| 충돌 1 책임 분기 | 1/6 (2단계), 3/6 회피 | 상호 배타 충돌 → 회피 |
| 충돌 2 본문 톤 | — (literal+paraphrase 합성) | 동시 만족 가능 충돌 → 합성 6/6 |

### 핵심 Finding 5건

1. **description은 형태로 통제할 수 없다.** 짧음·영어·예시·빈약 모두 베이스(한국어 long 4요소)보다 더 나빠지거나 같음. 길고 정확한 한국어 4요소가 *지금까지 본 형태 중* 그나마 가장 안정적 — 하지만 그것도 시나리오 3·4에서 0/2 실패.
2. **description이 실제 동작과 어긋나면 LLM은 그 거짓말을 따라간다 (§3-b F).** 잘못된 Tool로 분기 + storeName 같은 부수 데이터로 *그럴듯한 거짓 응답* 합성 — *description = LLM이 신뢰하는 단 하나의 API 문서*.
3. **description의 신호가 너무 약하면 tool calling protocol 자체가 망가진다 (B 짧음·C 영어·E 빈약).** 모델이 응답 본문에 raw function-call JSON을 직접 박거나 Tool을 아예 안 부름.
4. **BaedalPrompt 메타 룰만이 cancelOrder 호출률을 끌어올렸다 (A 동맹).** 0/2 → 2/6 (33%). 여전히 빈약하지만 *유일하게 효과를 본 변형*. Round 1의 *"항상 한국어로만" 룰* 이 즉시 차단됐던 것과 같이, **시스템 프롬프트는 description보다 위계가 위**라는 강한 가설.
5. **description ↔ BaedalPrompt 충돌의 두 양상**:
   - **상호 배타적 충돌 → 회피** (충돌 1: 2단계 호출 vs 1단계 단독 → 0 Tool 3/6, 단독 호출 2/6, 정확히 2단계 호출 1/6)
   - **동시 만족 가능 충돌 → 합성** (충돌 2: literal vs paraphrase → 6/6 둘 다 동시에 등장)
   - → *충돌의 형식에 따라 모델 대응이 달라진다* 는 일반화 가설.

### Raw 로그

> 의미 단위로 통합 (8 → 4 파일). 미션 본체(E·F)는 채점 단위라 분리 유지, 확장은 형태·충돌별로 묶음.

| 구분 | 변형 | 파일 (섹션) |
|---|---|---|
| **미션 본체 §3-b** | E 빈약 | [stage3b-variant-E-sparse.txt](raw/stage3b-variant-E-sparse.txt) |
| | F 오해 유발 | [stage3b-variant-F-misleading.txt](raw/stage3b-variant-F-misleading.txt) |
| **확장 형태 §3-a** | A 동맹 / B 짧음 / C 영어 / D 예시 | [stage3a-form-variants.txt](raw/stage3a-form-variants.txt) — `[A]`·`[B]`·`[C]`·`[D]` 섹션 |
| **확장 충돌 §3-c** | 충돌 1 책임 분기 / 충돌 2 본문 톤 | [stage3c-conflicts.txt](raw/stage3c-conflicts.txt) — `[충돌 1]`·`[충돌 2]` 섹션 |

### 최종 코드 상태

이번 §3 측정 후 코드는 **베이스라인(§1단계 시점)으로 복원**:

- `BaedalPrompt.java` — `[Tool 사용 규칙]` 섹션 제거(변형 A·충돌 1·2의 잔재 모두 정리).
- `OrderTools.java` — 3개 description 모두 베이스(한국어 long 4요소). 변형 B·C·D·E·F·충돌의 잔재 정리.

변형 A가 cancelOrder 호출률을 가장 잘 끌어올렸지만 *33%로 여전히 빈약* → 본 PR엔 적용하지 않고 다음 라운드 후보로 둠. (예: few-shot + BaedalPrompt 룰을 *동시에* 적용했을 때의 누적 효과 측정, 또는 2단계 LLM 호출(분류 전용 second-pass)로 전환.)

