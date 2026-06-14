# 3단계: HandoffDetector + 상담원 전환 + Structured Output

## 구현 요약

- `HandoffDetector.detect()` — 우선순위 `EXPLICIT → LEGAL → ANGER` 순으로 판별
- `AssistantController` / `SupportController` — LLM 호출 **전에** Handoff 선검사
- `SupportController` Handoff 응답: `Category=ETC, Urgency=HIGH, requiresHumanAgent=true` 수동 조립
- 모든 Handoff 응답에 연결 번호 `1600-0987` 포함

---

## 상담원 전환 시나리오 3종

### 시나리오 1 — EXPLICIT_REQUEST

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: hand-1-time" \
  -d "{\"message\":\"상담원이랑 직접 얘기하고 싶어요\"}"
```

**응답**

```
네, 바로 상담원에게 연결해 드릴게요. 잠시만 기다려 주세요.
상담원 직통 번호: 1600-0987
```

**서버 로그**

```
2026-06-14T17:00:50.821+09:00 INFO  [Assistant] sessionId=hand-1-time, message=상담원이랑 직접 얘기하고 싶어요
2026-06-14T17:00:50.822+09:00 INFO  [Assistant] Handoff 감지 — reason=EXPLICIT_REQUEST
```

> `LLM 호출 완료` 로그가 찍히지 않았다. 컨트롤러 레벨에서 `handoffDetector.detect()`가 `EXPLICIT_REQUEST`를 반환하고 즉시 메시지를 돌려보냈다. 서버 로그 기준 요청 로그와 Handoff 감지 로그 차이는 1ms다. `InputGuardrailAdvisor`, `MessageChatMemoryAdvisor`, `QuestionAnswerAdvisor` 등 Advisor 체인 전체가 실행되지 않아 Memory 조회, RAG 임베딩, LLM 생성 비용이 모두 0이다. 정상 Tool 호출 케이스(56,116ms)와 비교하면 이 응답은 수만 배 빠르다. 고객이 "상담원 연결"을 원하는 순간 AI가 답변을 생성하는 것은 오히려 장애물이 된다. 즉각 이탈 처리로 고객 경험과 시스템 비용을 모두 줄일 수 있다.

---

### 시나리오 2 — LEGAL_ISSUE

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: hand-2" \
  -d "{\"message\":\"이거 너무 화나서 소비자원에 신고할 거예요\"}"
```

**응답**

```
법적·민원 관련 사안은 전문 상담원이 직접 도와드리겠습니다.
상담원 직통 번호: 1600-0987 로 연결해 드릴게요.
```

**서버 로그**

```
INFO  [Assistant] sessionId=hand-2, message=이거 너무 화나서 소비자원에 신고할 거예요
INFO  [Assistant] Handoff 감지 — reason=LEGAL_ISSUE
```

> "소비자원"과 "화나서"가 모두 포함됐지만 `LEGAL`이 `ANGER`보다 먼저 검사되므로 `LEGAL_ISSUE`로 트리거된다. 우선순위가 반대였다면 이 케이스는 `HIGH_EMOTION`으로 분류됐을 것이다. 분류 결과는 같지만 이유가 달라진다. `LEGAL_ISSUE`로 분류하면 백오피스 시스템에서 법무팀에 에스컬레이션하는 플래그를 붙일 수 있다. 단순 분노(`HIGH_EMOTION`)와 법적 민원(`LEGAL_ISSUE`)은 상담원에게 넘길 때 전달 맥락이 다르다. 우선순위 결정은 "어떤 분류가 상담원에게 더 정확한 맥락을 전달하는가"에 달려 있다.

---

### 시나리오 3 — HIGH_EMOTION

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: hand-3" \
  -d "{\"message\":\"나 너무 화나는데 답답해 죽겠네\"}"
```

**응답**

```
많이 불편하셨을 것 같아 진심으로 죄송합니다. 상담원이 직접 도와드리겠습니다.
상담원 직통 번호: 1600-0987
```

**서버 로그**

```
INFO  [Assistant] sessionId=hand-3, message=나 너무 화나는데 답답해 죽겠네
INFO  [Assistant] Handoff 감지 — reason=HIGH_EMOTION
```

> `ANGER_PATTERNS`의 "화나", "죽겠"에 매칭됐다. 응답이 "많이 불편하셨을 것 같아 진심으로 죄송합니다"로 시작하는 이유는 `HandoffDecision.handoff(HandoffReason.HIGH_EMOTION, ...)`에 공감 문구가 하드코딩돼 있기 때문이다. LLM 호출 없이 즉시 반환되지만, 이 문구는 항상 고정이다. LLM 방식과 비교하면 속도는 규칙 기반이 압도적이지만 감정 공명 품질은 LLM이 높다. "극도로 화가 난 고객"에게 고정 문구가 충분한가는 서비스 맥락과 운영 비용을 함께 고려해 결정할 문제다.

---

### 시나리오 4 — 정상 상담 (비교용)

stage2의 `out-order-no-fp` 참조: `2024-1234 주문 어디쯤 왔어요?`

```
LLM 호출 완료 — 56116ms | 입력 토큰: 2887 | 출력 토큰: 117 | 총 토큰: 3004
```

---

## 정량 비교 표

| 시나리오 | 트리거 | 응답 시간 | LLM 호출 | 연결 번호 포함 |
|---|---|---|---|---|
| 1 (상담원 요청) | EXPLICIT_REQUEST | 로그 기준 1ms | ❌ | ✅ |
| 2 (소비자원 신고) | LEGAL_ISSUE | 즉시 반환(LLM 로그 없음) | ❌ | ✅ |
| 3 (감정 분노) | HIGH_EMOTION | 즉시 반환(LLM 로그 없음) | ❌ | ✅ |
| 4 (정상 Tool 호출) | — | 56,116ms | ✅ | — |

> Handoff 케이스는 `PerformanceLoggingAdvisor` 로그 자체가 찍히지 않는다 (LLM 미호출).
> 정상 케이스 대비 응답 속도 차이: 시나리오 1 기준 약 56,000배. (Windows 기준)

---

### `/api/v1/support` Handoff 응답 검증

**요청**

```bash
curl -s -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: support-h-time" \
  -d "{\"message\":\"상담원 연결해 주세요\"}"
```

**응답 (JSON)**

```json
{
  "summary": "네, 바로 상담원에게 연결해 드릴게요. 잠시만 기다려 주세요.\n상담원 직통 번호: 1600-0987",
  "category": "ETC",
  "urgency": "HIGH",
  "nextAction": "상담원 연결 진행",
  "neededInfo": [],
  "customerSentiment": "FRUSTRATED",
  "estimatedResolutionMinutes": 0,
  "requiresHumanAgent": true
}
```

**서버 로그**

```
2026-06-14T17:01:01.057+09:00 INFO  [Support] sessionId=support-h-time, message=상담원 연결해 주세요
2026-06-14T17:01:01.057+09:00 INFO  [Support] Handoff 감지 — reason=EXPLICIT_REQUEST
```

> `category=ETC`, `urgency=HIGH`, `requiresHumanAgent=true`, 세 필드 모두 스키마대로 반환됐다. `SupportController`는 Handoff 발동 시 LLM을 호출하지 않고 `SupportResponse`를 직접 조립했다. 요청 로그와 Handoff 감지 로그가 같은 밀리초에 찍혔고, `LLM 호출 완료` 로그는 없다. `category=ETC`는 아직 문의 분류가 이루어지지 않은 상태를 의미하고, `urgency=HIGH`는 지연 없는 상담원 연결이 필요하다는 신호다. `requiresHumanAgent=true`가 JSON 필드로 포함됨으로써 이 응답을 소비하는 상위 라우팅 시스템이 별도 파싱 없이 즉시 상담원 큐에 넣을 수 있다. Structured Output의 핵심 가치는 "고객에게 전달하는 자연어"가 아니라 "시스템이 읽는 결정 신호"를 함께 생산하는 것이다.

---

## 실패 관찰 — 규칙 기반의 한계

### 우회 문장 실험

| 입력 | 탐지 결과 | 이유 |
|---|---|---|
| "상 담 원 연결해줘" (띄어쓰기) | ❌ 미탐지 | 정규식이 "상담원"을 연속 문자로 매칭하므로 공백 삽입으로 우회됨 |
| "진짜 너무너무 불편했습니다…" (완곡한 분노) | ❌ 미탐지 | ANGER_PATTERNS가 "화나", "짜증", "열받" 등 직접적 단어만 매칭, "불편" 미포함 |
| "agent plz" (영문 비정형) | ✅ 탐지 (EXPLICIT_REQUEST) | EXPLICIT_PATTERNS에 영문 "agent" 포함 |

**탐지 실패 케이스 1: 띄어쓰기 우회**

```
요청: "상 담 원 연결해줘"
응답: 상담 연결 중입니다. 잠시만 기다려 주세요. 어떤 주문을 말씀하시는 건가요?
로그: LLM 호출 완료 — 18526ms | 입력 토큰: 1373 | 출력 토큰: 27 | 총 토큰: 1400
```

LLM은 문맥을 이해해 "상담 연결"이라고 응답했지만 Handoff가 발동되지 않아 LLM 비용이 발생했다.

**탐지 실패 케이스 2: 완곡한 분노**

```
요청: "진짜 너무너무 불편했습니다…"
응답: 고객님, 정말 불편하셨다니 죄송합니다. 어떤 문제로 불편하셨는지 자세히 말씀해 주실 수 있을까요?
로그: LLM 호출 완료 — 24111ms | 입력 토큰: 1375 | 출력 토큰: 50 | 총 토큰: 1425
```

**분류 LLM으로 보강하면 어떻게 개선되는가?**

별도 "안전 분류기" ChatClient를 InputGuardrail 직후에 배치해 입력을 `SAFE / HANDOFF_EXPLICIT / HANDOFF_LEGAL / HANDOFF_ANGER / SENSITIVE`로 분류한다.

- 분류 LLM은 "상 담 원" (띄어쓰기), "너무너무 불편" 같은 변형도 의미로 판단하므로 규칙 기반의 FN을 크게 줄일 수 있다
- 단, 비용과 지연이 2배. 모든 요청에 분류 LLM을 적용하면 Handoff가 아닌 일반 질문도 추가 LLM 호출이 발생한다
- 전략: 분류 LLM을 규칙 기반이 통과시킨 요청에만 2차로 적용 (규칙으로 명백한 케이스 먼저, 애매한 케이스만 분류 LLM에 넘김)

---

## 설계 결정 문서

### Q1. 왜 EXPLICIT → LEGAL → ANGER 순인가? ANGER를 먼저 두면?

ANGER 패턴("화나", "짜증")은 일상적 표현과 겹칠 수 있어 False Positive 위험이 높다.          
예: "화나는 영화 봤는데 배달 시키려고요" → ANGER 먼저 검사하면 불필요한 Handoff 발생.

EXPLICIT("상담원 연결")은 명시적 의도가 분명하고 오탐 위험이 가장 낮다.
LEGAL("소비자원 신고")은 법적 사안이라 빠른 처리가 중요하고, 오탐 시에도 상담원 연결이 적절한 대응이다.

ANGER를 먼저 두면
- "이거 화나서 환불 받고 싶어요" → ANGER로 Handoff, 실제로는 환불 안내로 해결 가능한 케이스였음
- 불필요한 Handoff로 상담원 리소스 낭비, 고객도 더 긴 대기를 경험

---

### Q2. 왜 LLM 호출 전에 Handoff를 검사하나? Advisor 체인 안에서 처리하는 것과 비교한 장단점?

**LLM 호출 전 (현재 방식)**
- 장점: LLM 비용·지연 0. Handoff 결정이 수 ms 이내에 완료된다.
- 장점: LLM이 "상담원 연결" 요청에 대해 엉뚱한 답변을 생성할 기회 자체가 없다.
- 단점: Advisor 체인에서 얻을 수 있는 대화 이력(Memory)이나 RAG 컨텍스트 없이 입력 원문만 보고 판별해야 한다.

**Advisor 체인 안에서 처리 (대안)**
- 장점: Memory가 먼저 실행되므로 "아까도 상담원 요청했잖아요" 같은 맥락을 활용 가능.
- 단점: Memory, RAG가 실행된 이후라 불필요한 비용·지연 발생. 체인 구조가 복잡해짐.

결론: 명시적 키워드 기반 Handoff는 LLM 전에 처리하는 것이 효율적이다.
분류 LLM 기반 Handoff(감정 분석)라면 Memory 컨텍스트가 필요할 수 있어 체인 안이 더 적합할 수 있다.

---

### Q3. 감정 분석을 LLM으로 vs 규칙 기반: 비용 / 지연 / 정확도 트레이드오프?

| 항목 | 규칙 기반 (현재) | LLM 기반 |
|---|---|---|
| 비용 | 0 | 요청마다 토큰 비용 |
| 지연 | < 1ms | 수백~수천 ms |
| 정확도 | 직접 단어만 탐지, FN 높음 | 맥락·변형 이해, FN 낮음 |
| 유지보수 | 패턴 추가 필요 | 프롬프트 조정으로 커버 |
| 결정론성 | 동일 입력 = 동일 결과 | 확률적, 재현 어려움 |

실무에서는 규칙 기반으로 명백한 케이스를 무지연으로 처리하고, 통과한 요청 중 "불편", "실망" 등 약한 감정 단어가 있는 경우에만 LLM 분류기를 2차 적용.
전체 트래픽의 5~10%만 LLM 분류기를 태우면 비용과 지연 증가를 최소화할 수 있을 것이다.
