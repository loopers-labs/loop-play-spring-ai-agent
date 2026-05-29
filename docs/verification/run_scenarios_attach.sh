#!/bin/bash
# 검증 하니스 — "이미 떠 있는 서버"용 러너 (서버를 띄우지/죽이지 않는다).
#
# run_scenarios.sh 와 동작은 같으나, 서버 기동·종료를 하지 않고 이미 LISTEN 중인
# 8080 서버에 시나리오를 던진다. 시나리오별 로그는 logback DEV_FILE 이 남기는
# logs/dev-console.log 를 슬라이싱해 만든다(콘솔 합집합 = 동작 축 신호 보존).
#
# 사용법(repo 루트 기준):
#   # 터미널 A: 서버 직접 기동 (dev 프로파일이 logs/dev-console.log 기록)
#   ./gradlew bootRun
#   # 터미널 B:
#   CASES=docs/week2/stage1/toolcalling_verification/cases.json MODEL=qwen2.5 bash docs/verification/run_scenarios_attach.sh
#
# 전제: jq 설치, 8080 서버 기동 중, logback DEV_FILE 활성(dev 프로파일).
#       다른 위치로 stdout 을 리다이렉트했다면 SERVER_LOG=/path/to/server.log 로 override.
#
# 산출물: <cases 파일 디렉터리>/responses/<cases_stem>/scenarioN.json, scenarioN_logs.log
#         (run_scenarios.sh / evaluate.py 와 동일 위치)

set -euo pipefail

trap 'echo "❌ 중단됨 (exit=$?) — 서버 연결 또는 직전 [Scenario] 단계를 확인하세요" >&2' ERR

MODEL="${MODEL:-qwen2.5}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASES="${CASES:?CASES 에 cases 파일 경로를 지정하세요. 예: CASES=docs/week2/stage1/toolcalling_verification/cases.json}"

command -v jq >/dev/null 2>&1 || { echo "jq required. brew install jq"; exit 1; }
[[ -f "$CASES" ]] || { echo "❌ cases 파일이 없습니다: $CASES" >&2; exit 1; }

# cases 파일을 절대경로화하고, 출력은 그 옆 responses/<stem>/ 에 떨어뜨린다.
CASES="$(cd "$(dirname "$CASES")" && pwd)/$(basename "$CASES")"
CASES_LABEL="$(basename "$CASES" .json)"
OUT="$(dirname "$CASES")/responses/$CASES_LABEL"

# 프로젝트 루트(git 최상위). SERVER_LOG 기본 경로(logs/dev-console.log) 계산에만 쓴다.
PROJECT_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$PROJECT_ROOT" ]] || PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

URL="http://localhost:8080/api/v1/assistant"

# logback DEV_FILE 이 남기는 파일. 다른 곳으로 stdout 을 흘렸다면 SERVER_LOG 로 override.
SERVER_LOG="${SERVER_LOG:-$PROJECT_ROOT/logs/dev-console.log}"

mkdir -p "$OUT"
echo "Model:       $MODEL"
echo "Cases:       $CASES"
echo "ProjectRoot: $PROJECT_ROOT"
echo "Output:      $OUT"
echo "ServerLog:   $SERVER_LOG"
echo "---"

# ── 서버가 떠 있는지 확인 (없으면 거부) ────────────────────────────
# 이 러너는 서버를 띄우지 않는다. 8080 이 LISTEN 이어야 진행한다.
if ! lsof -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "❌ 8080 포트에 떠 있는 서버가 없습니다. 먼저 서버를 띄우세요:" >&2
    echo "     ./gradlew bootRun" >&2
    exit 1
fi

# 로그 파일 확인 — 없으면 동작 축(log_tool/log_absent) 판정이 불가능하므로 중단.
if [[ ! -r "$SERVER_LOG" ]]; then
    echo "❌ 로그 파일을 읽을 수 없습니다: $SERVER_LOG" >&2
    echo "   - dev 프로파일로 기동했는지(logback DEV_FILE → logs/dev-console.log) 확인하세요." >&2
    echo "   - stdout 을 다른 파일로 흘렸다면 SERVER_LOG=/path/to/server.log 로 지정하세요." >&2
    exit 1
fi
echo "서버 LISTEN 확인됨 (8080), 로그: $SERVER_LOG"
echo "---"

# ── 한 시나리오 실행 + 응답/로그 저장 ──────────────────────────────
call() {
    local num="$1"
    local message="$2"
    local outfile="$OUT/scenario${num}.json"
    local logfile="$OUT/scenario${num}_logs.log"
    local req_time
    req_time="$(date -Iseconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"

    echo "[Scenario $num] $message"

    # 요청 직전 로그 끝 위치(줄 수). 동기 로그라 curl 후엔 이 요청 로그가 다 찍혀 있다.
    local log_before
    log_before="$(wc -l < "$SERVER_LOG")"

    local tmpfile meta body http_code elapsed
    tmpfile="$(mktemp)"
    meta="$(curl -s -X POST "$URL" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg msg "$message" '{message: $msg}')" \
        -w '%{http_code} %{time_total}' \
        -o "$tmpfile")"
    body="$(cat "$tmpfile")"; rm -f "$tmpfile"
    http_code="$(echo "$meta" | awk '{print $1}')"
    elapsed="$(echo "$meta" | awk '{print $2}')"

    jq -n \
        --argjson scenario "$num" \
        --arg model "$MODEL" \
        --arg message "$message" \
        --arg time "$req_time" \
        --arg body "$body" \
        --argjson httpCode "$http_code" \
        --argjson elapsed "$elapsed" \
        '{
            scenario: $scenario,
            model: $model,
            request: { message: $message, time: $time },
            response: { body: $body, httpCode: $httpCode, elapsedSeconds: $elapsed }
        }' > "$outfile"
    echo "  → saved: $outfile (http=$http_code, ${elapsed}s)"

    # 이 요청이 만든 로그 구간만 추출(logback CONSOLE/DEV_FILE 패턴 'HH:MM:SS LEVEL ...'만 남김).
    tail -n "+$((log_before + 1))" "$SERVER_LOG" \
        | grep -E '^[0-9]{2}:[0-9]{2}:[0-9]{2} ' \
        > "$logfile" || true
    if [[ ! -s "$logfile" ]]; then
        tail -n "+$((log_before + 1))" "$SERVER_LOG" > "$logfile" || true
    fi
    echo "  → logs:  $logfile ($(wc -l < "$logfile") lines)"
}

# ── cases 파일의 시나리오를 배열 순서대로 실행 ─────────────────────
count="$(jq '.cases | length' "$CASES")"
[[ "$count" -gt 0 ]] || { echo "❌ cases 가 비어 있습니다: $CASES" >&2; exit 1; }

for i in $(seq 0 $((count - 1))); do
    num="$(jq -r ".cases[$i].scenario" "$CASES")"
    msg="$(jq -r ".cases[$i].message" "$CASES")"
    if [[ -z "$msg" || "$msg" == "null" ]]; then
        echo "❌ cases[$i] 에 message 가 없습니다(이 러너는 message 필수)." >&2
        exit 1
    fi
    call "$num" "$msg"
done

echo "---"
echo "Done. $count 개 시나리오 응답이 $OUT 에 저장되었습니다."
echo ""
echo "다음 단계:"
echo "  MODEL=$MODEL python3 \"$SCRIPT_DIR/evaluate.py\" --cases \"$CASES\"   # 기대값과 코드 판정 → results.json"
echo "  → 사람용 2축 리포트는 'llm-scenario-verification' 스킬로 작성"
