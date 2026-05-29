# Tool description 한국어 vs 영어 — 측정 리포트

## 목차

- [TL;DR](#tldr)
- [1. 배경 및 동기](#1-배경-및-동기)
- [2. 테스트 케이스](#2-테스트-케이스)
- [3. 측정 결과](#3-측정-결과)
  - [항목별 측정 결과 요약](#항목별-측정-결과-요약)
  - [3-1. Tool 선택 정확도 — 차이 없음](#3-1-tool-선택-정확도--차이-없음)
  - [3-2. 파라미터 정확도 — orderId 동률, reason은 한국어가 더 구체적](#3-2-파라미터-정확도--orderid-동률-reason은-한국어가-더-구체적)
  - [3-3. 오발동(FP) / 미발동(FN) — 둘 다 0](#3-3-오발동fp--미발동fn--둘-다-0)
  - [3-4. 응답 정확성 — 차이 없음](#3-4-응답-정확성--차이-없음)
  - [3-5. 토큰 — 영어가 호출당 −77, 케이스마다 일정](#3-5-토큰--영어가-호출당-77-케이스마다-일정)
  - [3-6. 응답 시간 — 차이 없음(노이즈)](#3-6-응답-시간--차이-없음노이즈)
- [4. 한계 및 다음 단계](#4-한계-및-다음-단계)
- [5. 실험 설계 및 재현 정보](#5-실험-설계-및-재현-정보)
  - [실험 환경](#실험-환경)
  - [한 트라이얼이 도는 법 (용어 정의)](#한-트라이얼이-도는-법-용어-정의)
  - [판정·채점 기준 (항목별)](#판정채점-기준-항목별)
  - [재현 체크리스트](#재현-체크리스트)
- [부록 — Tool description 원문](#부록--tool-description-원문)


---

## TL;DR

**한국어 description 유지 권장.**

- Tool 선택·파라미터·오발동/미발동·응답 정확성 : **한국어·영어 동률(전부 만점)**
- 토큰 : 영어가 호출당 **−77(−3.5%)** 로 일관되게 적지만, 절대 비중이 작아 가독성·유지보수 비용을 넘어설 정도는 아님
- `reason` 인자의 구체성 : 한국어 `@ToolParam` 예시가 유리(`단순 변심` vs `고객 요청`) — **한국어 유지의 실질 근거**

---

## 1. 배경 및 동기

qwen2.5 모델에서 `@Tool`/`@ToolParam` description의 **언어(한국어 ↔ 영어)** 만 바꿨을 때 LLM의 Tool 사용·산출물이 달라지는지 측정한다. 

---

## 2. 테스트 케이스

Tool을 **써야 하는** 5종 + **쓰면 안 되는** 2종. 사용자 메시지는 모두 한국어이며 ko·en 측정에서 동일하다.

| 케이스 | 사용자 메시지 | 무엇을 보는가 | 기대 동작 | 파일 id |
|---|---|---|---|---|
| 배달 위치 | "주문번호 2024-1234 배달 어디쯤에 있어요?" | 배달 중 주문 조회 | `getDeliveryStatus` 호출 | c1 |
| 메뉴 조회 | "주문번호 2024-1234 어떤 메뉴 주문했어요?" | 주문 상세 조회 | `getOrderDetail` 호출 | c2 |
| 취소 — 가능 | "주문번호 2024-1235 방금 시킨 건데 취소해주세요" | CREATED → 취소 성공 | `cancelOrder` 호출 | c3 |
| 취소 — 불가 | "주문번호 2024-1236 취소해주세요" | 배달완료 → 취소 거절 | `cancelOrder` 호출 | c4 |
| 없는 주문 | "주문번호 2099-9999 배달 어디예요?" | 존재하지 않는 번호 | `getDeliveryStatus` → "없음" | c5 |
| 번호 없는 문의(환불 정책) | "환불 정책이 어떻게 되나요?" | 주문번호 없음 | **Tool 미호출**(되묻기) | n1 |
| 번호 없는 문의(내 주문) | "내 주문 어떻게 됐어요?" | 주문번호 없음 | **Tool 미호출**(되묻기) | n2 |

> 아래 표·서술은 모두 "케이스" 라벨로 표기한다. "파일 id"는 원자료(`c1_trial_1.json` 등) 추적용.

---

## 3. 측정 결과

description 언어를 **단일 변수**로 두고 6개 항목을 한국어/영어 각각 측정했다. 


### 항목별 측정 결과 요약

| # | 측정 항목                   | 단계 | 한국어 | 영어 | 차이 |
|---|-------------------------|---|---|---|---|
| 3-1 | Tool 선택 정확도             | 결정 | 25/25 (100%) | 25/25 (100%) | 없음 |
| 3-2 | 파라미터 (orderId / reason) | 결정 | 25/25 / 10/10 | 25/25 / 10/10 | **reason은 ko가 더 구체적** |
| 3-3 | 오발동 FP / 미발동 FN         | 결정 | 0/10 / 0/25 | 0/10 / 0/25 | 없음 |
| 3-4 | 응답 정확성                  | 결과 | 35/35 (100%) | 35/35 (100%) | 없음 |
| 3-5 | 토큰 (입력 토큰)              | 결과 | 2174 ± 5.5 | 2097 ± 5.5 | **−77 (−3.5%)** |
| 3-6 | 응답 시간 (초)               | 결과 | 2.21 ± 0.95 | 2.17 ± 0.67 | −0.04 (노이즈) |

**결정 단계는 두 언어가 완전히 동일하게 만점**, 차이는 **토큰(3-5)** 과 **reason 구체성(3-2)** 에서만 나타났다. 각 항목의 측정·채점 방법은 §5의 같은 번호(5-1~5-6) 참조.



### 3-1. Tool 선택 정확도 — 차이 없음

두 언어 모두 정답 Tool을 **25/25(100%)** 호출. 오선택(다른 Tool 호출) 0건.

### 3-2. 파라미터 정확도 — orderId 동률, reason은 한국어가 더 구체적

- **orderId**: 두 언어 모두 **25/25(100%)** 일치. 환각·오타 없음(없는 주문 2099-9999 포함).
- **reason**(취소 케이스, 사용자가 사유를 말하지 않음 → 모델이 description 예시·맥락에서 추론): 둘 다 의미 통함(**10/10**). 단, 한국어 예시가 더 구체적인 사유를 유도.

`@ToolParam`의 **예시(입력)** ↔ 모델이 채운 **reason(출력)**:

| description 언어 | `@ToolParam` reason 예시 (입력) | "취소 — 가능" 출력 | "취소 — 불가" 출력 |
|---|---|---|---|
| **한국어** | `취소 사유 (예: 단순 변심, 잘못 주문)` | `단순 변심` (5/5) | `고객 요청` (5/5) |
| **영어** | `e.g., changed my mind, wrong order` | `고객 요청` (5/5) | `고객 요청` (5/5) |

→ 한국어 예시의 `단순 변심`이 출력에 **그대로 전파**. 영어 예시는 특정 한국어 사유로 이어지지 못하고 generic한 `고객 요청`으로 수렴 — **한국어 유지의 실질 근거**.

### 3-3. 오발동(FP) / 미발동(FN) — 둘 다 0

- **FP**: Tool을 쓰면 안 되는 케이스(주문번호 없는 문의 n1,n2)에서 Tool 호출 **0/10**. 두 언어 모두 주문번호를 되묻거나 일반 안내로 응답.
- **FN**: Tool을 써야 하는 케이스에서 미호출 **0/25**.

### 3-4. 응답 정확성 — 차이 없음

두 언어 모두 **35/35(100%)**. 환각·outcome 오해석 0건. 예: "취소 — 불가"를 "취소됨"으로 잘못 안내한 트라이얼 없음, "없는 주문"을 가짜 배달정보로 채운 트라이얼 없음.

### 3-5. 토큰 — 영어가 호출당 −77, 케이스마다 일정

| 케이스 | 한국어 | 영어 | 차이 |
|---|---|---|---|
| 배달 위치 | 2178 | 2101 | −77 |
| 메뉴 조회 | 2178 | 2101 | −77 |
| 취소 — 가능 | 2180 | 2103 | −77 |
| 취소 — 불가 | 2174 | 2097 | −77 |
| 없는 주문 | 2176 | 2099 | −77 |
| 번호 없는 문의(환불 정책) | 2167 | 2090 | −77 |
| 번호 없는 문의(내 주문) | 2165 | 2088 | −77 |

모든 케이스에서 차이가 **정확히 −77**(영어 description이 그만큼 압축적). 같은 케이스 5회는 토큰이 매번 동일하고, 통합표의 `± 5.5`는 케이스 간 메시지 길이 차이일 뿐 측정 노이즈가 아니다 → 측정·해석 상세 §5-5.

### 3-6. 응답 시간 — 차이 없음(노이즈)

ko 2.21초 vs en 2.17초, 차이 −0.04초는 분산(±0.7~0.95) 범위 내. 측정 순서에 따른 편향 있음 → §4 한계.

---

## 4. 한계 및 다음 단계

> **약신호** = 차이가 보이긴 하나 표본이 작거나 인과가 불확실해 확정 못 하는 징후.

### 한계

| # | 내용 |
|---|---|
| 1 | **시스템 프롬프트가 도구 호출을 강제 — 결정 단계 결과의 핵심 confound** — 측정에 쓴 [`assistant_system_prompt.md`](../../../src/main/resources/prompts/assistant_system_prompt.md)에는 ① "Tool 사용 규칙"(주문번호가 있으면 **즉시 Tool 호출**, 미호출 **절대 금지**)과 ② 테스트 케이스와 **거의 동일한 few-shot 예시**(`getOrderDetail("2024-1234")`, `cancelOrder("2024-1235"/"2024-1237"/"2024-1238", "고객 요청")` 등)가 들어 있다. 따라서 도구 선택·orderId·FP/FN의 "ko=en 100%"는 description이 아니라 **프롬프트 규칙·예시가 주도**했을 가능성이 크다(description 언어가 결정 단계에 영향을 줄 여지가 사실상 차단됨). reason의 영어 `고객 요청`도 이 프롬프트 예시에서 왔을 수 있다 — 반대로 한국어가 description의 `단순 변심`으로 그 예시를 덮은 것은 오히려 ko description 영향의 증거. (토큰(3-5)은 프롬프트와 무관하게 순수 description 비용이라 영향 없음) |
| 2 | **응답시간 confound** — 한국어를 항상 먼저 측정 → Ollama 모델 최초 로드를 한국어가 흡수. 시간 절대값·언어 간 시간 비교는 보수적으로 해석. |
| 3 | **소규모(N=5, 케이스 7)** — 무차이 결론은 견고하나(전부 100%/0%), 약신호는 N 확대 필요. |
| 4 | **메뉴 조회 도착시간 (약신호)** — `getOrderDetail`이 `orderedAt`+`estimatedDeliveryAt`를 모두 노출 → 모델이 잔여시간 오산 여지. 실측: 한국어 3/5가 정확한 절대시각("3시 53분"), 영어 5/5가 "약 35분 후"(실제 잔여 15분과 다름). 환각이 아니라 필드 오산이라 description 언어와 인과는 불확실. |
| 5 | **Tool 메시지 사전 이슈(실험 무관)** — `cancelOrder`의 취소불가 메시지가 상태와 무관하게 "조리가 이미 시작되었습니다"로 하드코딩 → 배달완료 케이스에서도 "조리" 문구. 모델은 Tool 메시지를 충실히 전달했을 뿐. |
| 6 | **1차 측정 토큰 무효** — curl 응답 직후 서버 kill이 로그 flush보다 빨라 `[PERF]`/`[LLM #2]`가 잘림. kill 전 `[PERF]` 대기를 추가(`3d479ca`)해 2차에서 70/70 정상 수집. |

### 다음 단계

- **Tool 규칙·예시 없는 프롬프트로 재측정 (최우선)** — [`assistant_system_prompt_without_tool_rule.md`](../../../src/main/resources/prompts/assistant_system_prompt_without_tool_rule.md)(위 "Tool 사용 규칙"·"Tool 호출 예시" 절만 제거, 그 외 동일)로 동일 측정. 강제·예시가 없을 때 description 언어가 도구 선택·orderId·reason에 영향을 주는지 분리해, **결정 단계 무차이 결론(한계 #1)의 신뢰도를 직접 검증**한다.
- **영어 전환 재검토 시점**: Tool 수가 늘어 description 토큰 비중이 커질 때, 다국어 메시지가 들어올 때.
- **부수 발견(후속 과제)**: `getOrderDetail`이 `orderedAt`+`estimatedDeliveryAt`를 함께 노출하면 잔여 도착시간을 오산할 수 있음 → 잔여시간이 필요하면 View에서 계산된 단일 필드 제공 검토.
- **약신호 검증**: 메뉴 조회 도착시간·reason 구체성은 N 확대로 재검.

---

## 5. 실험 설계 및 재현 정보

### 실험 환경

| 항목                | 값                                                                                                  |
|-------------------|----------------------------------------------------------------------------------------------------|
| 모델                | qwen2.5:latest                                                                                     |
| temperature       | 0.3                                                                                                |
| Ollama 버전 / 실행 머신 | [확인 필요]                                                                                            |
| 반복                | 케이스당 N=5 (언어 2종 × 케이스 7 = 70 트라이얼)                                                                 |
| 재기동 정책            | 매 트라이얼 서버 재시작 (KV 캐시·주문 상태 초기화)                                                                    |
| 브랜치               | 한국어=`week2/feature01_v6_branch`, 영어=`week2/feature01_v6_tool_desc_en` (OrderTools description만 차이) |
| 측정 스크립트           | `run_desc_lang_experiment.sh`                                                                      |
| 시스템 프롬프트          | [assistant_system_prompt.md](../../../src/main/resources/prompts/assistant_system_prompt.md) - Tool 사용 규칙 있는 버전                                                   |
| 실행                | 2회 (1차 `20260524_152337`는 토큰 로그 잘림으로 토큰만 무효 / 2차 `20260524_153718`이 본 리포트)                         |
| 결과                | 70/70 http=200 (언어별 35)                                                                            |

### 한 트라이얼이 도는 법 (용어 정의)

Tool을 쓰는 요청 1건에서 LLM은 보통 **2번 호출**된다:

1. **LLM #1** — 시스템 프롬프트 + **Tool 정의**(여기에 description이 들어감) + 사용자 메시지를 받아 "어떤 Tool을 부를지" 판단
2. **Tool 실행** (`[Tool]` 로그)
3. **LLM #2** — Tool 결과까지 받아 최종 답변 작성

트라이얼마다 **응답 JSON 1개** + 그 트라이얼 전용 **`server.log` 1개**가 남는다. 실제 산출물("배달 위치", 한국어):

```jsonc
// 응답 JSON
{
  "caseId": "c1", "expectedTool": "getDeliveryStatus",
  "response": {
    "body": "현재 라이더는 역삼역 사거리 부근에서 배달 중이며, 약 15분 후 도착 예정입니다. 감사합니다.",
    "httpCode": 200,
    "elapsedSeconds": 4.041470
  }
}
```
```text
// server.log
[LLM #1] elapsed=2615ms input=2178 output=29       ← LLM#1 input 토큰 = 2178
[Tool]   getDeliveryStatus(orderId=2024-1234)      ← Tool + 인자
[LLM #2] elapsed=1312ms input=4485 output=67
[PERF]   elapsed=3951ms 총호출=2회 누적합계=6759     ← 이 요청의 총 토큰
```

- **LLM#1 input** = 첫 호출이 받는 입력(프롬프트) 토큰. description은 Tool 정의 안에 있어 LLM#1 input에 잡힌다.
- **`[PERF]` 누적합계** = 그 요청의 LLM 호출 전체의 input+output 합(총 토큰). 위 예: (2178+29)+(4485+67)=6759.
- 채점은 자동(로그·JSON에서 기계적으로) 또는 사람(의미 판단)으로 나뉘며, 각 항목을 케이스×언어별 N=5회 측정해 평균(과 분산)을 본다.
- **판정자**: 사람 채점 항목은 측정자 1인 + 루브릭, 애매한 트라이얼은 flag. (판정자 개인 신원 [확인 필요])

### 판정·채점 기준 (항목별)

#### 5-1. Tool 선택 정확도 〔자동〕
정답 Tool의 `[Tool]` 줄이 있으면 1, 없으면 0. (출처: server.log)

#### 5-2. 파라미터 정확도 〔orderId 자동 / reason 사람〕
- **orderId**: `[Tool]` 줄의 `orderId=` 값이 메시지의 주문번호와 정확히 일치하면 1.
- **reason**(cancelOrder): 의미 통하는 한국어면 1, 빈값·의미불명·환각이면 0. 사용자가 사유를 말하지 않은 경우 "고객 요청"·"단순 변심" 같은 무난한 기본값도 1로 인정(깨진 한국어만 0).

#### 5-3. 오발동(FP) / 미발동(FN) 〔자동〕
`[Tool]` 줄의 **유무**로 본다. 미발동 케이스에 `[Tool]` 줄이 생기면 FP(정상=0개), Tool 케이스에 `[Tool]` 줄이 없으면 FN.

#### 5-4. 응답 정확성 〔사람, 0/1 이진〕
응답 본문(`response.body`)이 아래 **3조건을 모두** 만족하면 1, 하나라도 어기면 0 (부분점수 없음):
1. **핵심 사실 정확** — Tool이 준 사실(메뉴·금액·상태·취소결과)을 정확히 전달
2. **환각 없음** — Tool이 주지 않은 정보를 지어내지 않음
3. **outcome 오해석 없음** — 예: 취소불가를 "취소됨"으로 안내 X, 없는 주문을 가짜 정보로 채움 X

> 이진으로 둔 이유: 다른 항목과 통일해 평균=성공률·분산 계산이 깔끔하고, 0.5는 주관적 흔들림만 키운다.

#### 5-5. 토큰 사용량 〔자동〕
**LLM#1의 input 토큰**으로 측정한다(description이 박히는 곳이라 비용이 가장 깨끗이 드러남).
- 같은 케이스 5회는 프롬프트가 고정이라 input 토큰이 매번 동일 → 케이스 내 분산 0. 통합표의 `± 5.5`(sd)는 7개 케이스의 메시지 길이 차이(2165~2180)일 뿐, 측정 노이즈가 아니다.
- `[PERF]` 누적합계(LLM#1+#2 총합)는 Tool 라운드트립 1~2회에 따라 출렁여(1345~6759) 언어 비교에 부적합 → 사용하지 않음.

#### 5-6. 응답 시간 〔자동〕
JSON의 `response.elapsedSeconds`(end-to-end, 초)를 그대로 기록.

### 재현 체크리스트

- **모델/설정**: qwen2.5:latest, temperature 0.3
- **description 원문 위치**: `src/main/java/com/baedal/support/tool/OrderTools.java` — 한국어판은 `week2/feature01_v6_branch`, 영어판은 `week2/feature01_v6_tool_desc_en` (두 브랜치는 OrderTools description만 차이)
- **실행**: `run_desc_lang_experiment.sh` (현재 브랜치=한국어 baseline, 영어 브랜치 존재 상태에서 실행 → 자동으로 ko→en 측정)
- **시행 횟수**: 케이스당 5회 × 언어 2종 = 70 트라이얼
- **판정**: 자동(5-1, 5-2 orderId, 5-3, 5-5, 5-6) + 사람(5-2 reason, 5-4), 판정자 1인

---

## 부록 — Tool description 원문

`OrderTools.java`의 `@Tool`/`@ToolParam` description 전문. 한국어=`week2/feature01_v6_branch`, 영어=`week2/feature01_v6_tool_desc_en` (두 브랜치는 이 description만 차이).

### getOrderDetail

**한국어**
```
주문 상세 정보를 조회한다.
고객이 주문 메뉴, 금액, 주문 상태, 예상 배달 시간을 물을 때 호출한다.
orderId 형식: 'YYYY-XXXX' (예: 2024-1234).
존재하지 않는 주문번호면 null을 반환한다.
조회 중 시스템 오류가 발생하면 error 필드가 true인 응답을 반환한다.
이때는 고객에게 잠시 후 재시도나 상담사 연결을 안내한다.
```
**영어**
```
Retrieves order detail information.
Call this when the customer asks about the order menu, price, order status, or estimated delivery time.
orderId format: 'YYYY-XXXX' (e.g., 2024-1234).
Returns null if the order number does not exist.
If a system error occurs during lookup, returns a response with the error field set to true.
In that case, advise the customer to retry shortly or to be connected to an agent.
```
- `@ToolParam` orderId — 한국어 `조회할 주문번호 (예: 2024-1234)` / 영어 `Order number to look up (e.g., 2024-1234)`

### getDeliveryStatus

**한국어**
```
배달 상태와 라이더 위치를 조회한다.
고객이 배달 현황이나 도착 시간을 물을 때 호출한다.
배달 중인 주문(DELIVERING)에만 라이더 위치 정보가 유효하다.
orderId 형식: 'YYYY-XXXX' (예: 2024-1234).
존재하지 않는 주문번호면 null을 반환한다.
조회 중 시스템 오류가 발생하면 error 필드가 true인 응답을 반환한다.
이때는 고객에게 잠시 후 재시도나 상담사 연결을 안내한다.
```
**영어**
```
Retrieves the delivery status and rider location.
Call this when the customer asks about the delivery progress or arrival time.
Rider location information is valid only for an order in delivery (DELIVERING).
orderId format: 'YYYY-XXXX' (e.g., 2024-1234).
Returns null if the order number does not exist.
If a system error occurs during lookup, returns a response with the error field set to true.
In that case, advise the customer to retry shortly or to be connected to an agent.
```
- `@ToolParam` orderId — 한국어 `조회할 주문번호 (예: 2024-1234)` / 영어 `Order number to look up (e.g., 2024-1234)`

### cancelOrder

**한국어**
```
주문을 취소한다.
취소 가능 조건: CREATED 또는 ACCEPTED 상태만 가능.
COOKING 이후 상태(조리 시작됨)는 취소 불가.
이미 취소된 주문을 다시 요청하면 에러가 아닌 ALREADY_CANCELED를 반환한다(멱등).
결과는 CancelOrderResult의 outcome 필드로 성공/실패 사유를 확인할 수 있다.
```
**영어**
```
Cancels an order.
Cancelable condition: only CREATED or ACCEPTED status.
States from COOKING onward (cooking has started) cannot be canceled.
If an already-canceled order is requested again, returns ALREADY_CANCELED instead of an error (idempotent).
The outcome field of CancelOrderResult indicates the success/failure reason.
```
- `@ToolParam` orderId — 한국어 `취소할 주문번호 (예: 2024-1235)` / 영어 `Order number to cancel (e.g., 2024-1235)`
- `@ToolParam` reason — 한국어 `취소 사유 (예: 단순 변심, 잘못 주문)` / 영어 `Cancellation reason (e.g., changed my mind, wrong order)`
