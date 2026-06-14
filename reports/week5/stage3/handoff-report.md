# 3단계 — HandoffDetector + 상담원 전환 + Structured Output (평가축 ★)

> 3가지 트리거를 우선순위(EXPLICIT → LEGAL → ANGER)대로 판별하고, LLM 호출 전에 전환 응답을 반환한다.
> 측정 환경: Ollama `qwen2.5` + `qwen3-embedding:0.6b`, temp 0.3, num_ctx 8192, PgVector(pg16).

## 구현 요약

| 요소 | 값 / 위치 | 근거 |
|---|---|---|
| `HandoffDetector` | 규칙 기반, `detect(input)` | 우선순위 EXPLICIT → LEGAL → ANGER |
| 연결 번호 | `1600-0987` | 모든 전환 문구에 포함 — 전환이 실제 동작 가능함을 표시 |
| 검사 위치 | LLM 호출 **전** (Controller/Service) | Advisor 체인 안이 아니라 선검사 — 일관 문구 + 토큰/지연 0 |
| `/assistant` | `String` 반환 | handoff.message() 직접 반환 |
| `/support` | `SupportResponse` 수동 조립 | Category=ETC, Urgency=HIGH, nextAction="상담원 연결 진행", routing=AGENT_REVIEW |

## 상담원 전환 3종 + 정상 비교 (정량 ★)

| # | 입력 | 트리거 | client_ms | LLM 호출 | 1600-0987 |
|---|---|---|---:|---|---|
| 1 | 상담원이랑 직접 얘기하고 싶어요 | EXPLICIT_REQUEST | ~30 (warmup 82) | **없음** | O |
| 2 | 이거 너무 화나서 소비자원에 신고할 거예요 | **LEGAL_ISSUE** | 32 | **없음** | O |
| 3 | 나 너무 화나는데 답답해 죽겠네 | HIGH_EMOTION | 34 | **없음** | O |
| 정상 | 비 오는 날 배달 늦으면 보상? | (없음) | 26958 | 1회 (5222토큰) | X |

- 전환은 **수십 ms·LLM 0**, 정상은 **27초·5222토큰**. 약 800배 지연 차 — LLM 호출 전 선검사의 효과.
- 시나리오 2 가 우선순위의 핵심 증거: "너무 화나"(ANGER) + "소비자원/신고"(LEGAL)가 한 문장에 공존하는데 **LEGAL_ISSUE 로 판별**됐다. ANGER 를 먼저 뒀다면 HIGH_EMOTION 으로 분류돼 법적 사안 전용 응대를 놓쳤을 것.

서버 로그:
```
[Assistant] 상담원 전환 — reason=EXPLICIT_REQUEST
[Assistant] 상담원 전환 — reason=LEGAL_ISSUE
[Assistant] 상담원 전환 — reason=HIGH_EMOTION
```
세 케이스 모두 `[LLM]` 로그 없음 = LLM 미호출 증명.

## /support 구조화 조립 (스키마 준수)

`"상담원 바꿔주세요"` → `.entity()` 가 아니라 수동 조립:
```json
{ "summary":"상담원 전환(EXPLICIT_REQUEST) — LLM 호출 없이 반환됨.",
  "customerMessage":"네, 바로 상담원에게 연결해 드릴게요 … (상담원 연결: 1600-0987)",
  "category":"ETC", "urgency":"HIGH", "nextAction":"상담원 연결 진행",
  "recommendedRouting":"AGENT_REVIEW" }
```
평문 short-circuit 이 JSON 파싱을 깨는 문제(1단계 발견)를 피하면서 구조화 계약 유지.

## 실패 관찰 — 규칙 기반의 한계 (★)

| 우회 입력 | 결과 | 관찰 |
|---|---|---|
| `상 담 원 연결해주세요` (띄어쓰기) | **미탐지** → LLM(1759ms) | 정규식 "상담원"이 "상 담 원" 안 잡음. LLM 이 우연히 "상담원 연결" 응대했으나 구조화 전환·연결번호 없음 |
| `진짜 너무너무 불편했습니다 다시는 안 시킬래요` (완곡 분노) | **미탐지** → LLM(2797ms) | ANGER 패턴에 "불편"·"다시는" 없음. 분노지만 키워드 미일치 |
| `agent plz` (영문 비정형) | **탐지** (EXPLICIT) | 패턴에 `agent` 포함 — 영문은 오히려 잡힘 |

- 두 미탐지는 모두 LLM 으로 흘러 토큰·지연을 쓰고도 일관된 전환(연결번호 1600-0987)을 못 준다. 규칙 기반의 **낮은 재현율(FN)** 이 그대로 드러난 사례.
- 보강 방안: 입력을 정규화(공백 제거)한 뒤 매칭 + 감정 분류 LLM 으로 완곡 표현 보강. "상 담 원"은 `\s` 제거 전처리로, "불편/실망"은 분류기로 잡아야 한다.

## 설계 결정 (quest "왜?" 3종)

1. **왜 EXPLICIT → LEGAL → ANGER?** 명시적 요청은 가장 분명한 의사라 최우선. 법적/민원은 오판 시 리스크(소송·언론)가 가장 커 분노보다 앞에 둔다. ANGER 를 먼저 두면 시나리오 2("소비자원 신고" + 분노)가 HIGH_EMOTION 으로 빠져 법적 사안 전용 응대(전문 상담원)를 놓친다 — 실측으로 확인.

2. **왜 LLM 호출 전에 Handoff 를 검사?** (1) LLM 에 전환 문구 생성을 맡기면 매번 달라져 일관성 훼손, (2) LLM 의 "도움" 본능이 "조금만 더 도와드릴게요"로 전환을 회피해 분노 가중, (3) 토큰·지연 낭비. Advisor 체인 안에서 short-circuit 해도 비용 0 은 같지만, handoff 는 비즈니스 라우팅(상담원 시스템 연결)이라 Controller/Service 가 자연스럽고 `/support` 의 구조화 조립도 여기서만 가능하다. 트레이드오프: Advisor 였다면 모든 엔드포인트에 일괄 적용되지만 구조화 응답(SupportResponse) 조립이 어렵다.

3. **감정 분석 LLM vs 규칙 — 비용/지연/정확도?** 규칙은 비용·지연 0 이나 재현율이 낮다(띄어쓰기·완곡 우회에 취약, 위 관찰). LLM 분류기는 완곡·우회까지 잡아 정확도가 높지만 호출당 비용·지연이 붙어 전체가 2배(선택 심화 항목). 절충: 규칙으로 명시적 신호를 0비용으로 거르고, 규칙이 애매한 트래픽만 분류 LLM 으로 보내는 2단 구조.

## 최종 구성 (3단계 종료 시점)

- 신규: `guardrail/HandoffDetector`(detect 구현, HandoffDecision/HandoffReason)
- 수정: `AssistantController`(handoff 선검사, 빈입력 다음·LLM 전), `SupportService`(generateSupportResponse handoff 분기 + handoffResponse 조립)
- 측정: 전환 3종 LLM 0·~30ms / 정상 27초 / 우회 2건 미탐지(FN) + 1건 탐지 / `/support` 스키마 조립 확인
