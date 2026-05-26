# Step 2 — 단순 vs 구조화 프롬프트 정량 비교 (A1-A4 + C)

`/api/v1/prompt-lab` 호출. 5회 반복, `categoryConsistency` (최빈 카테고리 비율) 측정.

조건: temperature 0.3, Spring AI `BeanOutputConverter`가 `SupportResponse` JSON 스키마 자동 주입.

---

## 실험 A1 — 단순 프롬프트 + 명확 메시지

**systemPrompt**: `"당신은 배달 고객 상담 AI입니다."`
**message**: `"주문번호 2024-1234 배달 어디쯤에 있어요?"`

```json
{
  "totalRuns": 5,
  "categoryCounts": {"DELIVERY": 5},
  "urgencyCounts": {"NORMAL": 5},
  "categoryConsistency": 1.0
}
```

## 실험 A2 — 구조화 프롬프트 + 명확 메시지

**systemPrompt**: `BaedalPrompt.SYSTEM_PROMPT` (5섹션: 역할/규칙/금지/응답 포맷/urgency 판정 기준)
**message**: 동일

```json
{
  "totalRuns": 5,
  "categoryCounts": {"DELIVERY": 5},
  "urgencyCounts": {"NORMAL": 5},
  "categoryConsistency": 1.0
}
```

**A1 vs A2 관찰**: 명확 메시지에선 단순/구조화 차이 0. consistency 둘 다 1.0. **메시지 자체가 분류를 결정** — 더 모호한 메시지가 필요.

---

## 실험 A3 — 단순 프롬프트 + **모호 메시지**

**message**: `"음식이 이렇게 다 식어서 왔는데 이걸 누가 먹어요?"` (3중 모호성: DELIVERY / COMPLAINT / REFUND 모두 가능 + 감정 톤 + 보상 요구 우회 표현)

```json
{
  "totalRuns": 5,
  "categoryCounts": {"DELIVERY": 5},
  "urgencyCounts": {"NORMAL": 5},
  "categoryConsistency": 1.0
}
```

## 실험 A4 — 구조화 프롬프트 + 모호 메시지

**systemPrompt**: `BaedalPrompt.SYSTEM_PROMPT`
**message**: 동일

```json
{
  "totalRuns": 5,
  "categoryCounts": {"COMPLAINT": 5},
  "urgencyCounts": {"NORMAL": 5},
  "categoryConsistency": 1.0
}
```

---

## A3 vs A4 — 가장 결정적 발견

| 실험 | systemPrompt | category | consistency |
|---|---|---|---|
| A3 (단순) | "당신은 배달 고객 상담 AI입니다." | **DELIVERY** 5/5 | 1.0 |
| A4 (구조화) | BaedalPrompt 5섹션 | **COMPLAINT** 5/5 | 1.0 |

**같은 메시지인데 카테고리가 정반대**:
- 단순: "배달 + 식음" 키워드 → DELIVERY (단순 위치 추적 라인)
- 구조화: 톤 + 보상 요구 시그널 파악 → COMPLAINT (사고 조사 + 보상 라인)

**메트릭 한계**: `categoryConsistency`는 "안정성"만 측정. **A3와 A4 둘 다 1.0인데 라벨 자체가 다름** → 정확성을 measurement하지 못함. ground truth 없이는 "더 일관"이 곧 "더 정확"이 아님.

**구조화 프롬프트의 진짜 가치**: 의도/톤 파악 → 다운스트림 정확한 라우팅 (단순 프롬프트는 키워드 매칭 → 사고형 클레임을 단순 배달 추적으로 처리 = 라이더 평가 미반영, 보상 라인 누락).

---

## 실험 C — BaedalPrompt 개선 효과 (urgency 차원)

A4 결과 분석: category는 정확하나 `urgency: NORMAL 5/5`. 감정적 불만 + 보상 요구 우회인데 NORMAL은 부정확.
→ `BaedalPrompt.SYSTEM_PROMPT` 의 `[응답 포맷]` 섹션에 **urgency 판정 기준 4단계** 추가.

```
urgency 판정 기준:
- LOW: 단순 정보 요청 (FAQ, 일반 정책 확인)
- NORMAL: 진행 중 상태 확인, 일반 문의
- HIGH: 감정적 불만, 보상 요구, 시간 민감 (취소·환불·재배달), 사고형 클레임
- CRITICAL: 안전 사고 (위생·알레르기·신체 피해), 법적·보안 이슈
```

같은 모호 메시지(`"음식이 이렇게 다 식어서 왔는데 이걸 누가 먹어요?"`) 5회 호출.

**결과**:
```json
{
  "totalRuns": 5,
  "categoryCounts": {"COMPLAINT": 5},
  "urgencyCounts": {"HIGH": 1, "NORMAL": 4},
  "categoryConsistency": 1.0
}
```

| 실험 | urgency 분포 |
|---|---|
| A4 (before) | NORMAL 5/5 |
| **C (after)** | NORMAL 4/5, **HIGH 1/5** |

**해석**:
- 방향성 맞지만 5/5 변경엔 부족 — 1/5만 HIGH로 끌어올라감
- 가설: (1) "감정적 불만" wording이 LLM에게 충분히 specific하지 않음, (2) temperature 0.3 보수성, (3) n=5는 통계적으로 작음
- 페어 PR #6 (배정은)은 10회씩 × 3 케이스 × 3 temperature = 90 calls 했음. 다음 라운드 표본 크기 반영 자리.

---

## 페어 리뷰 후 발견 — BeanOutputConverter 영향 (단계 2 보강)

PR 후 페어 리뷰 (홍성혁, 배정은) 검토 중 짚은 통찰:

> Spring AI의 `BeanOutputConverter`는 `.entity(SupportResponse.class)` 호출 시 Java enum schema를 자동 프롬프트에 주입한다. 즉 PromptLab이 `defaultSystem(req.systemPrompt())`로 받은 단순/구조화 프롬프트와 **무관하게**, Spring AI는 `Category enum 6값 + Urgency 4값 + ResponsibleParty 4값` 같은 schema 힌트를 항상 끝에 붙인다.

**시사점**:
- A1/A2/A3/A4 모두 categoryConsistency = 1.0 나온 이유 중 **schema 자동 주입의 기여가 크다** — 처음엔 "메시지가 명확해서"로만 해석함
- 단순 vs 구조화의 **진짜 공정한 비교**는 schema 주입 없이 raw 텍스트 응답으로 받아야 함
- 그래도 결정 3 ("구조화 가치 = 위험 행동 차단")의 데이터 증거는 강해짐: schema 주입으로 category만 봐선 차이 못 잡고, B (안전 규칙) 영역에서만 차이 드러남
