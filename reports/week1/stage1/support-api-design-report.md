# 1단계 — 기본 API + System Prompt + Structured Output 설계 보고서

## 구현 요약

- **`BaedalPrompt.SYSTEM_PROMPT`** — **7섹션**: 역할 / 규칙 / 금지[8] / 분류 가이드[11 Category] / 응답 작성 / 정보 수집 / 보상 처리
- **`SupportResponse`** (12필드 record) + 5 enum (`Category` / `Intent` / `Urgency` / `ConfidenceLevel` / `RecommendedRouting`)
- **`SupportController.triage()`** — `POST /api/v1/support` → `defaultSystem` + `.entity(SupportResponse.class)`

## 시나리오 3종 응답 (`POST /api/v1/support`)

| # | 입력 요지 | category | intent | routing | missingInfo |
|---|---|---|---|---|---|
| 1 | 배달 위치 (주문번호 포함) | `DELIVERY` | `DELIVERY_LOCATION` | `AUTO` | `[]` |
| 2 | 주문 취소 + 환불 | `ORDER` | `ORDER_CANCEL` | `AUTO` | `[]` ⚠ |
| 3 | 라이더가 음식 엎음 | **`CLAIM`** | **`CLAIM_DAMAGED_FOOD`** | `AGENT_REVIEW` | `[]` ⚠ |

> ⚠ `missingInfo` 회귀: 직전 호출에선 시나리오 2·3 모두 `["orderNumber"]` 로 정확 식별되었으나 이번 호출은 `[]` — `temperature: 0.3` 비결정성 (2단계 정량 측정 대상).

**시나리오별 핵심 해설**
- **시나리오 1** — `AUTO + missingInfo=[]` 조합이므로 **Round 2에서 즉시 Tool Calling(배달 추적 API) 처리 가능**
- **시나리오 2** — 1차 의도가 "취소"라 `ORDER`로 분류. 환불은 `relatedCategories=["REFUND"]`로 표현되어야 자연스러우나 LLM이 누락
- **시나리오 3** — `DELIVERY`가 아닌 **`CLAIM`** + `AGENT_REVIEW` + `nextAction="보상 가능 여부 검토"` → 보상 위임 설계가 의도대로 작동

<details>
<summary>전체 응답 JSON 펼치기</summary>

```json
// 시나리오 1
{"summary":"고객이 주문번호와 배달 위치를 문의함.",
 "customerMessage":"주문번호 2024-1234 배달 어디쯤에 있어요?",
 "category":"DELIVERY","intent":"DELIVERY_LOCATION",
 "relatedCategories":["ORDER","DELIVERY"],"urgency":"NORMAL",
 "nextAction":"배송 상태 확인 후 위치 안내",
 "neededInfo":["orderNumber"],"missingInfo":[],
 "estimatedResolutionMinutes":5,"confidenceLevel":"MEDIUM","recommendedRouting":"AUTO"}

// 시나리오 2
{"summary":"고객이 주문 취소와 환불 소요 시간을 문의함.",
 "customerMessage":"주문 취소하고 싶은데, 환불은 얼마나 걸려요?",
 "category":"ORDER","intent":"ORDER_CANCEL","relatedCategories":[],"urgency":"NORMAL",
 "nextAction":"주문 상태 확인 후 취소 처리 진행",
 "neededInfo":["orderNumber"],"missingInfo":[],
 "estimatedResolutionMinutes":5,"confidenceLevel":"MEDIUM","recommendedRouting":"AUTO"}

// 시나리오 3
{"summary":"고객이 음식 훼손에 따른 보상을 문의함.",
 "customerMessage":"음식이 훼손되셔서 많이 속상하셨겠습니다. 주문번호와 상황을 확인한 뒤 보상 가능 여부를 검토해 안내드리겠습니다.",
 "category":"CLAIM","intent":"CLAIM_DAMAGED_FOOD","relatedCategories":["DELIVERY"],"urgency":"NORMAL",
 "nextAction":"주문 상태 확인 후 보상 가능 여부 검토",
 "neededInfo":["orderNumber"],"missingInfo":[],
 "estimatedResolutionMinutes":15,"confidenceLevel":"MEDIUM","recommendedRouting":"AGENT_REVIEW"}
```

</details>

## 설계 결정

### `[금지]` 8가지 규칙의 선택 근거

| # | 규칙 | 이유 |
|---|---|---|
| 1 | 타 플랫폼(쿠팡이츠/요기요) 추천·비교 금지 | 자사 문제 해결 집중, 비교 분쟁 방지 |
| 2 | 사장님/매장/라이더/타 고객 개인정보(연락처·실명·주소·GPS·계좌·결제 정보) 노출 금지 *(다른 고객 주문 내용은 5번)* | 개인정보 침해·안전 |
| 3 | **라이더 정확 위치는 *고객 응답*에 노출 금지**. 시스템 내부 추적·활용은 OK, 고객에겐 "배달 중/근처 도착/예상 도착"으로 변환 | 시스템 운영 ↔ 고객 노출 경계 분리. 라이더 안전 |
| 4 | 쿠폰·할인·보상·환불·재배송 확정 약속 금지. "확인 후 안내" 어구 사용 | 정책·증빙 확인 후 결정 영역 |
| 5 | 다른 고객 주문·결제·배송·요청사항 언급 금지 | 제3자 정보 노출 방지 |
| 6 | 미확인 주문/배송/결제/환불 상태 단정 금지 | LLM 환각(hallucination) 방지 |
| 7 | 내부 시스템명·API명·정책 세부·라우팅 정보 노출 금지 | 정책 악용 방지 |
| 8 | 책임·과실 임의 단정 금지 | 분쟁 확산 방지 |

> 8개 모두 운영 위험 영역이 서로 달라 어느 하나도 빼기 어렵다고 판단. 확장 후보: 의료·법률·세무 조언 금지, 약관 임의 해석 금지.

### `Category` enum 11개로 확장한 근거

초기 5개(ORDER/DELIVERY/REFUND/PAYMENT/ETC)로는 운영 흐름이 모호.
*"라이더가 음식 엎음"* → `DELIVERY`로 보면 처리 흐름 모호, `REFUND`로 보면 발생 원인 누락 → **`CLAIM`** 이 명확한 처리 라우팅 가능.

```text
ORDER · DELIVERY · PAYMENT · REFUND · CLAIM · MENU · STORE · COUPON · ACCOUNT · SYSTEM · ETC
```

카테고리별로 **추가 정보·API 호출·상담원 연결·SLA·라우팅이 달라지기 때문**에 분리.
지나친 세분화는 `categoryConsistency` 저하·관리 비용을 부르므로 Category는 **1차 도메인 수준**으로 유지하고, 구체 의도는 `intent`(26개), 다중 도메인은 `relatedCategories`로 표현.

### 추가 필드의 선택 근거

| 필드 | 추가 이유 / 비고 |
|---|---|
| `summary` / `customerMessage` **분리** | **청자 분리** — 내부 기록용(3인칭) vs 고객 노출용(존댓말). 두 필드의 작성 방식이 완전히 다름 |
| `intent` (26 enum) | Category 안의 구체 의도. **Round 2 Tool Calling에서 Intent → API 매핑** (예: `DELIVERY_LOCATION` → 배달 추적 API) |
| `relatedCategories` | 다중 도메인 표현 (시나리오 3 = CLAIM + DELIVERY + REFUND) |
| `neededInfo` / `missingInfo` **분리** | **Tool Calling 즉시 가능 여부 판단**. `missingInfo=[]` 이면 자동, 비면 `customerMessage`에서 그 정보 요청 |
| `estimatedResolutionMinutes` | 운영 SLA / 상담사 우선순위 |
| `confidenceLevel` (L/M/H) | LLM 자기 확신도 — `LOW`면 상담사 검토. **단, 과신 편향 있어 단독 신호로는 약함** |
| `recommendedRouting` | `urgency`(긴급도)와 별개 축의 **책임 주체**. `AUTO/AGENT_REVIEW/MANAGER_REVIEW/DELIVERY_PARTNER/STORE_CONFIRMATION` |
| ⛔ `suggestedCompensationType` ***제외*** | LLM이 보상 유형(쿠폰/환불/재배송) 직접 추천하면 `[금지] 4번`과 충돌 + 후속 시스템이 "AI가 추천했다"로 받아들일 위험. **`CLAIM` 분류 + `*_REVIEW` 라우팅으로 위임** |

### System Prompt를 7섹션으로 분리한 근거

Structured Output(JSON 12필드)을 반환하는 응답 구조에서는, 단일 응답 포맷 섹션에 12필드 작성 규칙을 모두 담으면 LLM이 우선순위를 잃는다.
따라서 자연어 응답 흐름이 아닌 **영역별 가이드**로 분리:

| 분리 섹션 | 역할 |
|---|---|
| `[분류 가이드]` | 11 Category enum 의미 명시 (이름만으론 LLM 분류 일관성 부족) |
| `[응답 작성 가이드]` | `summary`/`customerMessage` 청자 분리 · echo 금지 · 예시 2종 |
| `[정보 수집 가이드]` | `neededInfo`/`missingInfo` 분리 + 메시지 기반 식별 규칙 |
| `[보상 처리 가이드]` | `[금지] 4번`의 *적극적 행동 지침* — "안 하는 것"은 금지에, "대신 어떻게"는 가이드에 |

## 관찰 노트

**잘 작동 ✓**
- 시나리오 3이 `CLAIM`/`CLAIM_DAMAGED_FOOD` 로 정확 분류 (Category 세분화 효과)
- 시나리오 3 `customerMessage`가 공감 + 보상 검토 안내로 작성 (예시 매칭 케이스)
- 보상 단정 표현 없음, `nextAction`이 "검토" 어구로 통일
- 한국어 응답 안정화 (`[규칙]` 1줄 추가 효과)

**한계 → 2단계 Prompt Lab 분석 재료**
- 시나리오 1·2 `customerMessage` echo (qwen2.5 한계, 강화 프롬프트에도 잔존)
- `urgency` 모두 `NORMAL` (분류 가이드 부재)
- `relatedCategories`에 `category` 중복 또는 무관 카테고리(`SYSTEM` 등) 출현 (LLM 미세 결함·비결정성)
- `missingInfo` / `confidenceLevel` 호출별 변동 (`temperature: 0.3` 비결정성)

**자가 점검**

| 검증 항목 | 결과 |
|---|---|
| `bootRun` 성공 + `/api/v1/support` 응답 | ✓ |
| System Prompt 섹션 분리 | ✓ (7섹션) |
| 시나리오별 `category`/`urgency` 분기 | △ (category ✓, urgency 모두 NORMAL) |
| 추가 필드 선택 근거 문서화 | ✓ |
