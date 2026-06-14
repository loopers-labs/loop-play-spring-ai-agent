# 5주차 3단계 — 상담원 전환(Handoff) 검증 및 설계 결정

## 1. 목표·전제

- [`HandoffDetector`](../../../src/main/java/com/baedal/support/guardrail/HandoffDetector.java)가 `EXPLICIT_REQUEST → LEGAL_ISSUE → HIGH_EMOTION` 우선순위로 전환 신호를 판별하고, 두 컨트롤러가 **LLM 호출 전에** 선검사해 전환 안내(연결 번호 `1600-0987` 포함)를 반환하는지 검증한다.
- 판정 2축:
  - **동작 축** — 전환 로그 `[Handoff] reason=<트리거>` 출현 + `[LLM #`/`[PERF]` 미출현(LLM 미호출).
  - **내용 축** — 응답 본문에 트리거별 안내 문구 + 연결 번호 `1600-0987` 노출.
- 전제: PgVector 기동 + 지식 적재, Ollama `qwen2.5`. 실행은 [`run_scenarios.sh`](handoff_scenario/run_scenarios.sh)(서버 자체 기동 → `cases.json` 순차 POST → 응답·로그·`evidence.tsv` 저장).
- 모든 수치는 [`handoff_scenario/cases.json`](handoff_scenario/cases.json) + `responses/evidence.tsv` 실측과 동기화.

## 2. 검증 결과 (동작 축)

| # | 입력 | 기대 트리거 | 전환 로그 | LLM 호출 | 응답시간(ms) |
|---|---|---|---|---|---|
| 1 | 상담원이랑 직접 얘기하고 싶어요 | EXPLICIT_REQUEST | O | X | 38 |
| 2 | 이거 너무 화나서 소비자원에 신고할 거예요 | LEGAL_ISSUE | O | X | 2 |
| 3 | 나 너무 화나는데 답답해 죽겠네 | HIGH_EMOTION | O | X | 2 |
| 6 | agent plz | EXPLICIT_REQUEST | O | X | 2 |
| 4 | 상 담 원 연결 | NONE(미탐지) | X | O | 21765 |
| 5 | 진짜 너무너무 불편했습니다… | NONE(미탐지) | X | O | 21256 |
| 7 | 비 오는 날 배달 늦으면 보상 받나요? | NONE(정상) | X | O | 12136 |

- 왜 1·2·3·6은 LLM 호출 X인가 — handoff 선검사가 `chatClient.call()` **앞**에서 가로채 전환 응답을 즉시 반환 → Ollama 추론 0 → 응답 2~38 ms.
- 왜 4·5·7은 LLM 호출 O인가 — 전환 신호 미탐지(4·5) 또는 신호 없음(7)으로 선검사를 통과해 LLM까지 흘러감 → 12~22초.

## 3. 응답 본문 발췌 (내용 축)

- **S1 EXPLICIT_REQUEST** — `네, 바로 상담원에게 연결해 드릴게요. 잠시만 기다려 주세요. (연결 번호: 1600-0987)`
- **S2 LEGAL_ISSUE** — `법적/민원 관련 사안은 전문 상담원이 직접 도와드릴게요. 바로 연결해 드리겠습니다. (연결 번호: 1600-0987)`
- **S3 HIGH_EMOTION** — `많이 불편하셨을 것 같아요. 죄송합니다. 바로 상담원에게 연결해 드릴게요. (연결 번호: 1600-0987)`
- **S6 EXPLICIT_REQUEST** — S1과 동일 문구(`human|agent` 패턴 매치).

---

## 4. 실패 관찰 — 규칙 기반의 한계

### 우회 3종 결과

| 우회 입력 | 의도 | 판별 | 사유 |
|---|---|---|---|
| 상 담 원 연결 | 공백 분리 | 미탐지(FN) | `상담원` 패턴이 글자 사이 공백을 못 잡음(`직원\s*연결`도 불일치) |
| 진짜 너무너무 불편했습니다… | 완곡한 분노 | 미탐지(FN) | `화/짜증/빡쳐/답답해 죽` 등 ANGER 키워드 부재 |
| agent plz | 영문 비정형 | 탐지(EXPLICIT) | `human\|agent` 매치 — 단 `agent`는 일반 명사(`user agent` 등) 오탐(FP) 위험 |

### 탐지 실패가 부른 2차 피해 — 연결 번호 환각 (캡처)

- 미탐지된 **S4·S5는 LLM까지 흘러갔고, LLM이 잘못된 연결 번호를 환각**했다:
  - **S4** `상 담 원 연결` → `상담원 연결을 도와드리겠습니다. 고객센터 번호는 1588-0000입니다. …`
  - **S5** `진짜 너무너무 불편했습니다…` → `… 상담원 연결을 도와드릴까요? 고객센터 번호는 1588-0000입니다. …`
- 규칙이 잡았으면 정확한 `1600-0987`을 안내했을 입력이, 미탐지로 LLM에 도달하자 **존재하지 않는 `1588-0000`을 지어냈다.**
- 시사점: 전환 규칙의 FN은 단순 "전환 누락"에 그치지 않고, **LLM이 핵심 정보(연결처)를 환각해 고객에게 오정보를 주는** 정확성·일관성 붕괴로 번진다.

## 5. [설계 결정] 감정 분석 LLM vs 규칙 기반: 비용/지연/정확도 트레이드오프

### 축별 비교

| 축 | 규칙 기반(현재) | 분류 LLM |
|---|---|---|
| 비용 | 0(정규식, 토큰 없음) | 호출당 토큰 비용 발생 |
| 지연 | 수 ms(실측 2~38) | 수백 ms ~ 수 초 |
| 정확도 | 명시적 신호엔 정밀(FP 적음), 우회·완곡엔 미탐(FN 많음) | 맥락·오타·완곡에 강함(recall↑), 비결정적·과민(FP)·환각 위험 |

- **비용/지연** — 규칙은 정규식 매칭이라 토큰 0·수 ms로 끝난다(cost-0). LLM 분류는 전건에 태우면 토큰 비용과 수백 ms~수 초 지연이 매 요청 붙는다(위 §2 표의 12~22초가 LLM 왕복 비용).
- **정확도** — 규칙은 `상담원`·`소비자원` 같은 명시 신호엔 거의 틀리지 않지만(FP 적음), 위 실패 관찰처럼 `상 담 원`(공백)·완곡한 분노를 못 잡는다(FN 많음). LLM은 이런 변형·맥락을 잘 잡아 recall이 오르지만, 비결정적이고 과민 전환(FP)이나 환각 가능.

### 보강 방향 — 규칙 + LLM 2단계 하이브리드

1. **규칙(빠른 경로)** — 명확한 명시적/법적 신호를 0원·수 ms로 즉시 전환(cost-0 유지).
2. **경량 분류 LLM(보강 경로)** — 규칙이 미탐지한 입력에만 적용, `의도 + 감정 점수` 산출 후 임계치 초과 시 전환.

- **왜 전건 LLM 분류가 아닌가** — 모든 입력을 LLM에 보내면 cost-0 이점을 잃고 지연이 붙는다. 규칙으로 거른 **나머지에만** 분류기를 태워 비용·지연·정확도를 절충한다.
- **함께 설계할 것** — 분류 신뢰도 임계, 임계 미달 시 휴먼 폴백 경로, 누적 불만(반복 실패 = `REPEATED_FAILURE`) 같은 맥락 신호의 세션 단위 집계.

---
## 6. [설계 결정] 왜 LLM 호출 "전에" 검사하나? (Advisor 체인 처리와 비교)

### 공통 전제

- 전환 대상은 사람 상담원이 받을 사안이라 LLM 추론은 무의미한 토큰·지연이다. 어디서 막든 "LLM 왕복을 건너뛴다"는 목표는 같다. 차이는 **어느 지점에서 short-circuit 하느냐**다. 위 §2 표가 그 효과를 실측으로 보여준다(전환 4건 2~38 ms·LLM 미호출 vs 일반 12~22초).

### (1) 선택 — 컨트롤러 선검사 (`chatClient.prompt()` 호출 앞)

#### 장점 ① 엔드포인트별 반환 타입을 자유롭게 조립 
- `/assistant`는 `String`, `/support`는 `SupportResponse`
(category=ETC·urgency=HIGH·escalationRequired=true)로 수동 조립한다. 컨트롤러는 자기 응답 계약을 알기 때문에 가능하다.
#### 장점 ② 체인 진입 자체를 건너뜀
- `ChatClient.prompt()` 빌드, Memory advisor의 대화 이력 로드, RAG 검색 준비 등 advisor 부수 효과까지 0이 된다.
#### 단점 : 코드 중복

- 두 컨트롤러에 동일한 선검사 블록이 들어간다(DRY 위반). detect() 로직 자체는 `HandoffDetector` 한 곳에 모았으나 호출부는 중복.

### (2) 대안 — Advisor 체인 안에서 처리 (InputGuardrailAdvisor처럼 작은 order)

#### 장점 ① 단일 지점
- 체인에 한 번 끼우면 두 컨트롤러가 공유, 중복 제거.
#### 장점 ② 가드 정책을 한 계층에 모음
- InputGuardrail(order=5)과 같은 패턴으로 가드 정책을 한 계층에 모음(일관성).
#### 단점 ① 구조화 응답 조립이 어렵다 — advisor는 "LLM 대화 텍스트"만 다룬다
- Advisor는 LLM 호출 파이프라인 안의 인터셉터다: `ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain)`.
  - 입력 `ChatClientRequest` = LLM에 보낼 프롬프트
  - 출력 `ChatClientResponse` = LLM이 생성한 자연어 메시지(`AssistantMessage`)를 감싼 객체.
  - 즉 advisor의 데이터 단위는 `ChatResponse → Generation → AssistantMessage`의 비정형 텍스트(+메타데이터)이고, 도메인 타입(`SupportResponse`)은 이 계층에 존재하지 않는다(2단계 `OutputGuardrailAdvisor`가 이 텍스트를 꺼내 마스킹).
- 반환 타입은 advisor 체인이 끝난 **뒤** 컨트롤러가 정한다:
  - `/assistant` → `.call().content()` — 텍스트 그대로 `String`
  - `/support` → `.call().entity(SupportResponse.class)` — 텍스트를 JSON 파싱해 객체로
  - 이 변환은 체인 종료 후의 일이라 **advisor는 자기 위에서 누가 `String`/`SupportResponse`로 받을지 모른다. 그저 텍스트를 만들 뿐이다.**
- 그래서 advisor가 전환 응답을 일률적으로 만들면 `String`(`/assistant`)엔 맞지만, `/support`의 `SupportResponse` 구조화 계약은 깨거나 엔드포인트 분기 로직을 advisor에 넣어야 한다.
#### 단점 ② 절감 폭이 약간 작다
- short-circuit 하더라도 `ChatClient.prompt()`/체인 진입까지는 일어나, 컨트롤러 선검사보다 절감 폭이 약간 작다.

### 결론

- 엔드포인트마다 반환 타입이 다른 점이 결정적이라 **컨트롤러 선검사**를 택했다. 중복은 `HandoffDetector`로 로직을 모아 완화한다.

---

## 7. [설계 결정] 왜 우선순위가 EXPLICIT → LEGAL → ANGER 인가? (ANGER를 먼저 두면?)

- **EXPLICIT_REQUEST 최우선** — 고객이 "상담원/직원 연결"을 직접 말하면 의도가 가장 명확하다. 감정·법적 추측이 필요 없으므로 가장 먼저 확정한다.
- **LEGAL_ISSUE가 ANGER보다 위** — "화나서 소비자원에 신고하겠다"처럼 분노와 법적 신호가 겹칠 때, 더 치명적인 법적/민원 사안으로 격상해야 전문 상담원이 즉시 받는다.
- **HIGH_EMOTION 최후** — 감정 키워드(`화나`·`짜증`·`답답해 죽`)는 매칭 폭이 가장 넓어 오탐 위험이 크다. 명시적 요청·법적 신호가 없을 때만 마지막으로 적용한다.
- **ANGER를 먼저 두면** — 위 S2(`이거 너무 화나서 소비자원에 신고할 거예요`)는 분노(`너무 화나`)와 법적(`소비자원`·`신고`)이 **동시 매치**되므로 `HIGH_EMOTION`으로 분류돼 "감정 위로" 톤만 나가고 **법적 사안의 긴급 대응 경로를 놓친다**. 실측에서 S2가 `LEGAL_ISSUE`로 판별된 것이 우선순위 `LEGAL > ANGER`가 코드에 박혔음을 실증한다.


---
## 8. 실험 재현

```
MODEL=qwen2.5 bash docs/week5/stage3/handoff_scenario/run_scenarios.sh
```

- 산출물: `responses/scenarioN.json`(응답 본문), `scenarioN_logs.log`(요청별 로그 구간), `evidence.tsv`(증거 요약). `responses/`는 `.gitignore`.
- ⚠️ CLAUDE.md 실험 규칙: 임의 ping/헬스체크 금지. 서버 기동 확인은 러너 내장 로그 grep(`Started`)만 사용.
