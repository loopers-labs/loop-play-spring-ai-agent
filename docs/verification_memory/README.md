# Chat Memory 1단계 검증 (memory_verification)

> 1단계(A~I) 구현 후 Chat Memory가 의도대로 동작하는지 end-to-end로 검증한다.
> 시나리오 3종: ① 이력 누적 → ② 세션 분리 → ③ clear.

## 대상

`ChatMemoryConfig`(Repository/ChatMemory/Advisor), `SessionController`(messages/ids/clear),
`AssistantController`(X-Session-Id + conversationId 주입). 모델은 `qwen2.5`.

## 실행 (repo 루트 기준)

러너·평가기(`H`)와 cases(`S`)는 디렉터리가 분리돼 있다.

```bash
H=docs/verification_memory                 # 러너 + 평가기
S=docs/week3/stage1/memory_verification    # cases + 산출물

# 1) 시나리오 실행 (서버 자동 기동·종료) — step을 순서대로 호출
CASES=$S/cases.json MODEL=qwen2.5 bash $H/run_memory_scenarios.sh

# 2) 코드 판정 → results.json
MODEL=qwen2.5 python3 $H/evaluate_memory.py --cases $S/cases.json
```

산출물은 `$S/responses/cases/` 아래(`step<N>.json`, `step<N>_logs.log`, `server.log`, `results.json`).
생성 산출물이라 git에는 올리지 않는다(루트 `.gitignore`로 `responses/` 제외).

## cases 스키마 (기존 하니스와 차이)

step 배열을 순서대로 실행한다. 한 step = 한 HTTP 호출 + 기대값.

| 필드 | 의미 |
|---|---|
| `step` | 정수 ID (출력 `step<N>.json`) |
| `method` | `POST` / `GET` / `DELETE` (기본 POST) |
| `path` | 호출 경로 |
| `session` | (선택) `X-Session-Id` 헤더 값 |
| `message` | (선택, POST) 사용자 발화 → `{message}` 바디 |
| `expected.http_code` | 기대 HTTP 코드 |
| `expected.response_match` | 응답 본문 부분문자열 식 (`&&`/`\|\|`/`()`) |
| `expected.body_include` | 본문에 모두 있어야 하는 부분문자열 목록 |
| `expected.body_absent` | 본문에 있으면 FAIL인 부분문자열 목록 |
| `expected.message_count` | GET messages 본문(JSON 배열) 길이 == n |
| `expected.message_min` | 배열 길이 >= n |
| `expected.types_include` | 배열의 `type`(USER/ASSISTANT/…) 포함 여부 |
| `expected.log_tool` | 서버 로그 슬라이스(`step<N>_logs.log`)에 있어야 하는 부분문자열 (tool 호출 흔적, 예: `getDeliveryStatus(orderId=2024-1234`) |
| `expected.log_absent` | 로그에 있으면 FAIL인 부분문자열 목록 (예: `["[Tool]"]` = tool 미호출 단언) |

**주석 필드(러너·평가기가 무시, 문서용)**: `_comment`(파일 최상단 메모), `hypothesis`(step의 기대 동작 서술), `_note`(특정 step에 대한 부연 — 예: "이상 단언이라 FAIL이 기대됨"). `expected` 밖에 자유롭게 둘 수 있고 판정에 영향 없다.

## cases.json 작성법 (새 시나리오 만들기)

다른 상황을 검증하려면 이 파일만 고치면 된다(스크립트 수정 불필요). 기본 골격:

```json
{
  "_comment": "무엇을 검증하는지 한 줄 메모",
  "cases": [
    { "step": 1, "name": "...", "method": "POST", "path": "...", "session": "...",
      "message": "...", "hypothesis": "...", "expected": { ... } }
  ]
}
```

### 작성 규칙

1. **배열 순서 = 실행 순서.** 위 step의 부수효과(메모리 저장/삭제)가 아래 step에 그대로 남는다.
   "대화를 쌓는 POST → 결과를 확인하는 GET" 순으로 배치한다.
2. **`step`** 은 1,2,3… 고유 번호. 출력 파일명(`step<N>.json`)이 된다.
3. **같은 대화로 묶고 싶으면 `session`(X-Session-Id) 값을 같게**, 분리하려면 다르게 준다.
   POST에만 필요하고, 관리 엔드포인트(messages/ids/clear)는 path에 세션이 들어가므로 생략한다.

### method별로 쓰는 필드 / 단언

| 하려는 것 | method + path | message | 주로 쓰는 expected |
|---|---|---|---|
| LLM과 대화(이력 적재) | POST `/api/v1/assistant` | 필요 | `http_code`, `response_match`, `log_tool` / `log_absent` |
| 저장된 메시지 조회 | GET `/api/v1/session/{id}/messages` | — | `message_count` / `message_min`, `types_include` |
| 세션 목록 조회 | GET `/api/v1/session/ids` | — | `body_include` / `body_absent` |
| 세션 비우기 | DELETE `/api/v1/session/{id}` | — | `http_code` |

- `message_count`(==) vs `message_min`(>=): 정확한 건수를 알면 `count`, Tool 호출로 메시지가
  더 끼어들 수 있어 하한만 보장하려면 `min`.
- **tool 호출 검증은 `response_match`가 아니라 `log_tool`로 한다.** `response_match`는 실제 tool 호출과
  시스템 프롬프트 few-shot 예시 복사를 **구분하지 못한다**(예: getDeliveryStatus 미호출인데 "역삼역 사거리"가
  나옴). 호출이 일어나야 하면 `log_tool: "getDeliveryStatus(orderId=..."`, 안 일어나야 정상이면
  `log_absent: ["[Tool]"]`로 결정적으로 단언한다.
- `response_match`는 LLM 응답이 비결정적이므로 **거의 확실히 나오는 짧은 토큰**만 건다. 자유서술 문장은
  단언하지 말고 `hypothesis`로만 남겨 보조 관찰한다.

### expected 단언 종류 (평가기가 코드로 검사)

`evaluate_memory.py`가 step별 응답을 읽어 아래 단언을 코드로 판정한다. 한 step에 여러 개를 함께 둘 수 있고, **모두 통과해야 그 step이 pass**다.

| 단언 | 검사 내용 |
|---|---|
| `http_code` | 응답 코드 일치 |
| `response_match` | 응답 본문 부분문자열 식 (`&&` / `\|\|` / `()`) |
| `body_include` | 본문에 모두 포함돼야 하는 문자열 목록 |
| `body_absent` | 본문에 있으면 FAIL인 문자열 목록 |
| `message_count` | messages(JSON 배열) 길이 == n |
| `message_min` | messages 배열 길이 >= n |
| `types_include` | 배열의 `type`(USER/ASSISTANT/…) 집합 포함 여부 |
| `log_tool` | 서버 로그에 tool 호출 흔적(`[Tool] …`) 부분문자열이 **있어야** 통과 |
| `log_absent` | 목록의 문자열이 로그에 **하나라도 있으면** FAIL (예: tool 미호출 단언) |

### 판정 방식 (evaluate_memory.py)

평가기는 step마다 **입력 3개를 짝지어** `expected`와 대조한다.

| 입력 | 출처 | 쓰는 단언 |
|---|---|---|
| 응답(httpCode·body) | `step<N>.json` | `http_code`, `response_match`, `body_*`, `message_*`, `types_include` |
| 서버 로그 슬라이스 | `step<N>_logs.log` | `log_tool`, `log_absent` |
| 기대값 | cases의 `expected` | (검사 기준) |

- **`expected`에 있는 키만 검사한다.** 안 쓴 단언은 건너뛰므로, step마다 필요한 것만 골라 넣으면 된다.
- **검사 방식**: 문자열류(`response_match`·`body_*`·`log_*`)는 부분문자열 `in` 매칭(`response_match`는 `&&`/`\|\|`/`()` 식), 숫자류(`message_count`·`message_min`)는 `==`/`>=`. `body_*`는 **응답 본문**, `log_*`는 **로그 슬라이스**를 본다.
- **합산은 AND**: 한 step의 모든 단언이 통과해야 그 step이 pass. 하나라도 실패하면 FAIL.
  - 예) step8: `http_code` ✅ + `response_match:"역삼역 사거리"` ✅(few-shot 복사라도 통과) + `log_tool:"getDeliveryStatus(orderId=2024-1234"` ❌(실제 미호출) → **FAIL**. `log_tool`이 `response_match`만으론 놓치는 "미호출"을 잡아낸다.
- **출력**: `responses/<stem>/results.json`(step별 checks + `summary.passed/total`), 콘솔에 실패 단언의 detail, 종료코드 0(전부 통과)/1(하나라도 실패).

```
cases(expected) ┐
step<N>.json    ┼→ expected의 키별 검사(in / == / >=) → record → all(AND) → step pass/fail → results.json
step<N>_logs.log┘
```

### 예: "세션 격리" 한 줄 추가

sess-X로 대화 → sess-Y messages에 sess-X 주문번호가 없어야 함:

```json
{ "step": 10, "name": "격리 확인", "method": "GET",
  "path": "/api/v1/session/sess-Y/messages",
  "expected": { "http_code": 200, "body_absent": ["2024-1234"] } }
```

작성 후 실행은 위 "실행" 절과 동일(`CASES=$S/cases.json … bash $H/run_memory_scenarios.sh`).

## 합격 기준 (결정적)

| 시나리오 | 합격 |
|---|---|
| ① 이력 누적 | step3에 USER·ASSISTANT 포함, 메시지 4건 이상 |
| ② 세션 분리 | step5 ids에 sess-A·sess-B 공존, step6에 2024-1234 미혼입 |
| ③ clear | step7 200, step8 빈 배열(0건) |

대명사 해결(step2·9)은 LLM 비결정성 때문에 하드 단언 없이 응답 본문을 보조 관찰한다.
