#!/bin/bash
# Chat Memory 전용 검증 러너 — cases 파일(JSON)의 step을 순서대로 호출한다.
#
# 기존 run_scenarios.sh 와 달리, step 마다 method(POST/GET/DELETE) + path + 선택적
# session(X-Session-Id 헤더) + 선택적 message(POST 바디)를 읽어 호출한다.
# 세션 헤더와 관리 엔드포인트(messages/ids/clear)가 필요한 Memory 검증을 위한 것.
#
# 사용법(repo 루트 기준):
#   CASES=docs/week3/stage1/memory_verification/cases.json MODEL=qwen2.5 bash docs/verification_memory/run_memory_scenarios.sh
#
# 전제: jq 설치.
# 산출물: <cases 파일 디렉터리>/responses/<cases_stem>/step<N>.json, step<N>_logs.log
#         (evaluate_memory.py 출력 위치와 일치)

set -euo pipefail

trap 'echo "❌ 중단됨 (exit=$?) — 서버 연결 또는 직전 [Step] 단계를 확인하세요" >&2' ERR

MODEL="${MODEL:-qwen2.5}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASES="${CASES:?CASES 에 cases 파일 경로를 지정하세요. 예: CASES=docs/week3/stage1/memory_verification/cases.json}"

command -v jq >/dev/null 2>&1 || { echo "jq required. brew install jq"; exit 1; }
[[ -f "$CASES" ]] || { echo "❌ cases 파일이 없습니다: $CASES" >&2; exit 1; }

CASES="$(cd "$(dirname "$CASES")" && pwd)/$(basename "$CASES")"
CASES_LABEL="$(basename "$CASES" .json)"
OUT="$(dirname "$CASES")/responses/$CASES_LABEL"

PROJECT_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$PROJECT_ROOT" ]] || PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
[[ -x "$PROJECT_ROOT/gradlew" ]] || { echo "❌ gradlew 를 찾지 못했습니다: $PROJECT_ROOT/gradlew" >&2; exit 1; }

BASE="http://localhost:8080"

mkdir -p "$OUT"
SERVER_LOG="$OUT/server.log"
echo "Model:       $MODEL"
echo "Cases:       $CASES"
echo "ProjectRoot: $PROJECT_ROOT"
echo "Output:      $OUT"
echo "ServerLog:   $SERVER_LOG"
echo "---"

# ── 서버 기동 (이미 8080 점유 시 거부) ──────────────────────────────
if lsof -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "❌ 이미 8080 포트가 사용 중입니다. (lsof -i:8080 으로 확인 후 종료)" >&2
    exit 1
fi

echo "서버 기동 중... (로그: $SERVER_LOG)"
: > "$SERVER_LOG"
( cd "$PROJECT_ROOT" && ./gradlew bootRun ) > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
    echo "서버 종료 중 (pid: $SERVER_PID)..."
    kill "$SERVER_PID" 2>/dev/null || true
    lsof -ti:8080 2>/dev/null | xargs kill 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

READY_RE='Started .*Application|Tomcat started on port'
echo -n "서버 기동 대기"
for _ in $(seq 1 120); do
    if grep -Eq "$READY_RE" "$SERVER_LOG" 2>/dev/null; then
        echo " → up"; break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo ""; echo "❌ 서버 프로세스가 기동 중 종료되었습니다. 로그 마지막 부분:" >&2
        tail -n 30 "$SERVER_LOG" >&2; exit 1
    fi
    echo -n "."; sleep 1
done

if ! grep -Eq "$READY_RE" "$SERVER_LOG" 2>/dev/null; then
    echo ""; echo "❌ 서버 기동 완료 신호가 로그에 없습니다. 로그 마지막 부분:" >&2
    tail -n 30 "$SERVER_LOG" >&2; exit 1
fi
sleep 2
echo "---"

# ── 한 step 실행 + 응답/로그 저장 ──────────────────────────────────
call() {
    local num="$1" method="$2" path="$3" session="$4" message="$5"
    local outfile="$OUT/step${num}.json"
    local logfile="$OUT/step${num}_logs.log"
    local req_time
    req_time="$(date -Iseconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"

    echo "[Step $num] $method $path${session:+ (X-Session-Id: $session)}${message:+ — $message}"

    local log_before
    log_before="$(wc -l < "$SERVER_LOG")"

    # curl 인자 조립: 메서드, 세션 헤더, POST 바디.
    local -a args=(-s -X "$method" -w '%{http_code} %{time_total}')
    [[ -n "$session" ]] && args+=(-H "X-Session-Id: $session")
    if [[ "$method" == "POST" ]]; then
        args+=(-H "Content-Type: application/json"
               -d "$(jq -n --arg msg "$message" '{message: $msg}')")
    fi

    local tmpfile meta body http_code elapsed
    tmpfile="$(mktemp)"
    meta="$(curl "${args[@]}" -o "$tmpfile" "$BASE$path")"
    body="$(cat "$tmpfile")"; rm -f "$tmpfile"
    http_code="$(echo "$meta" | awk '{print $1}')"
    elapsed="$(echo "$meta" | awk '{print $2}')"

    jq -n \
        --argjson step "$num" \
        --arg model "$MODEL" \
        --arg method "$method" \
        --arg path "$path" \
        --arg session "$session" \
        --arg message "$message" \
        --arg time "$req_time" \
        --arg body "$body" \
        --argjson httpCode "$http_code" \
        --argjson elapsed "$elapsed" \
        '{
            step: $step,
            model: $model,
            request: { method: $method, path: $path, session: $session, message: $message, time: $time },
            response: { body: $body, httpCode: $httpCode, elapsedSeconds: $elapsed }
        }' > "$outfile"
    echo "  → saved: $outfile (http=$http_code, ${elapsed}s)"

    tail -n "+$((log_before + 1))" "$SERVER_LOG" \
        | grep -E '^[0-9]{2}:[0-9]{2}:[0-9]{2} ' \
        > "$logfile" || true
    if [[ ! -s "$logfile" ]]; then
        tail -n "+$((log_before + 1))" "$SERVER_LOG" > "$logfile" || true
    fi
    echo "  → logs:  $logfile ($(wc -l < "$logfile") lines)"
}

# ── cases 파일의 step 을 배열 순서대로 실행 ───────────────────────
count="$(jq '.cases | length' "$CASES")"
[[ "$count" -gt 0 ]] || { echo "❌ cases 가 비어 있습니다: $CASES" >&2; exit 1; }

for i in $(seq 0 $((count - 1))); do
    num="$(jq -r ".cases[$i].step" "$CASES")"
    method="$(jq -r ".cases[$i].method // \"POST\"" "$CASES")"
    path="$(jq -r ".cases[$i].path" "$CASES")"
    session="$(jq -r ".cases[$i].session // \"\"" "$CASES")"
    message="$(jq -r ".cases[$i].message // \"\"" "$CASES")"
    if [[ -z "$path" || "$path" == "null" ]]; then
        echo "❌ cases[$i] 에 path 가 없습니다." >&2; exit 1
    fi
    call "$num" "$method" "$path" "$session" "$message"
done

echo "---"
echo "Done. $count 개 step 응답이 $OUT 에 저장되었습니다."
echo ""
echo "다음 단계:"
echo "  MODEL=$MODEL python3 \"$SCRIPT_DIR/evaluate_memory.py\" --cases \"$CASES\"   # 기대값과 코드 판정 → results.json"
