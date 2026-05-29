# 공유 검증 하니스 (verification harness)

시나리오 단위로 LLM/에이전트 동작을 검증하는 **공유 러너 + 평가기**. 스크립트는 여기 단벌로 두고, 시나리오(`cases*.json`)는 각 스테이지 디렉터리에 둔다.

```
docs/verification/
├── run_scenarios.sh          서버 기동·종료 + cases의 message를 순서대로 호출 + 응답·로그 저장
├── run_scenarios_attach.sh   이미 떠 있는 서버에 던짐(기동·종료 안 함) + 응답·로그 저장
└── evaluate.py               cases 기대값과 코드로 대조 → results.json (pass/fail)

docs/week2/stage1/toolcalling_verification/   cases.json (+ responses/, README)
docs/week2/stage2/idempotency_verification/   cases.json, cases_idempotency_removed.json (+ responses/, README)
```

## 파이프라인

```
run_scenarios.sh  →  <cases_dir>/responses/<cases_stem>/scenarioN.json, scenarioN_logs.log
evaluate.py       →  같은 폴더에 results.json (코드 판정)
llm-scenario-verification 스킬  →  report.md (사람용 2축 리포트)
```

산출물은 **cases 파일 옆**(`<cases_dir>/responses/<cases_stem>/`)에 떨어진다. 러너와 평가기가 같은 규칙을 쓰므로 위치가 자동으로 일치한다.

## 사용법 (repo 루트 기준)

```bash
H=docs/verification
S=docs/week2/stage1/toolcalling_verification

# 1) 시나리오 실행 (서버 자동 기동·종료). CASES 필수.
CASES=$S/cases.json MODEL=qwen2.5 bash $H/run_scenarios.sh

# 2) 코드 판정 → results.json
MODEL=qwen2.5 python3 $H/evaluate.py --cases $S/cases.json

# 3) 사람용 2축 리포트는 'llm-scenario-verification' 스킬로 작성
```

### 이미 떠 있는 서버에 던지기 (서버를 직접 기동해 둔 경우)

반복 검증 시 매번 서버를 다시 띄우지 않으려면 `run_scenarios_attach.sh`를 쓴다. 이 러너는 서버를 기동·종료하지 않고, 떠 있는 8080 서버에 시나리오만 던진다.

```bash
# 터미널 A: 서버 직접 기동 (dev 프로파일이 logs/dev-console.log 에 콘솔 전체 로그를 기록)
./gradlew bootRun

# 터미널 B: 떠 있는 서버에 시나리오만 던지기 (1)단계만 대체, (2)·(3)은 동일)
CASES=$S/cases.json MODEL=qwen2.5 bash $H/run_scenarios_attach.sh
# stdout 을 다른 파일로 흘렸다면: SERVER_LOG=/path/to/server.log CASES=... bash $H/run_scenarios_attach.sh
```

- 시나리오별 로그는 logback `DEV_FILE`(dev 전용)이 남기는 `logs/dev-console.log`를 슬라이싱해 만든다 → `run_scenarios.sh`와 동일한 2축 검증이 유지된다.
- 서버가 안 떠 있으면(8080 미LISTEN) 즉시 거부한다. 로그 파일이 없으면(`logs/dev-console.log` 부재) `SERVER_LOG`로 경로를 지정하라고 안내한다.

- `CASES`(러너)와 `--cases`(평가기)에 **같은 cases 파일**을 주면 출력 위치가 자동 일치한다.
- `run_scenarios.sh`는 `gradlew`(프로젝트 루트)를 git 최상위에서 자동 탐색하므로 실행 위치에 무관하다.
- `jq` 필요(`brew install jq`).
- **대상 빌드 조건**(가드 유지/제거 등)은 각 스테이지의 README를 따른다.

## cases 파일 스키마

한 case = **(요청 message + 기대 expected)**. 배열 순서 = 실행 순서.

| 필드 | 의미 |
|---|---|
| `scenario` | 정수 ID (출력 파일명 `scenarioN.json`) |
| `name` | 짧은 이름 |
| `message` | API로 보낼 사용자 발화 (러너가 읽음) |
| `hypothesis` | 기대 동작 서술(리포트용) |
| `expected.http_code` | 기대 HTTP 코드 |
| `expected.response_match` | 응답 본문 부분문자열 식 (`&&` / `\|\|` / `()`) |
| `expected.log_tool` | 슬라이스 로그에 있어야 하는 부분문자열 (툴 호출/상태전이 신호) |
| `expected.log_absent` | 로그에 있으면 FAIL인 키워드 목록 |

새 시나리오 세트는 `cases_<용도>.json`을 추가하고 `CASES=`로 지정하면 된다(스크립트 수정 불필요).
