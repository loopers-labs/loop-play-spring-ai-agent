#!/bin/bash
# 공유 검증 하니스 — 시나리오를 cases 파일(JSON)에서 읽는 범용 러너.
#
# 시나리오 message 를 cases 파일의 각 case.message 에서 읽어 배열 순서대로 호출한다.
# 스크립트는 공유 위치(docs/verification)에 단벌로 두고, 각 스테이지는 cases*.json 만 둔다.
#
# 사용법(repo 루트 기준):
#   CASES=docs/week2/stage1/toolcalling_verification/cases.json MODEL=qwen2.5 bash docs/verification/run_scenarios.sh
#
# 전제: jq 설치. 대상 빌드 조건(가드 유지/제거 등)은 각 스테이지의 README.md 참고.
#
# 산출물: <cases 파일 디렉터리>/responses/<cases_stem>/scenarioN.json, scenarioN_logs.log
#         (cases 파일 옆에 떨어진다 → evaluate.py 출력 위치와 일치)

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

# 프로젝트 루트(=gradlew 위치): git 최상위. 실패 시 스크립트 위치 기준 2단계 위(docs/verification → 루트).
PROJECT_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$PROJECT_ROOT" ]] || PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
[[ -x "$PROJECT_ROOT/gradlew" ]] || { echo "❌ gradlew 를 찾지 못했습니다: $PROJECT_ROOT/gradlew" >&2; exit 1; }

URL="http://localhost:8080/api/v1/assistant"

mkdir -p "$OUT"
SERVER_LOG="$OUT/server.log"
echo "Model:       $MODEL"
echo "Cases:       $CASES"
echo "ProjectRoot: $PROJECT_ROOT"
echo "Output:      $OUT"
echo "ServerLog:   $SERVER_LOG"
echo "---"

# ── 서버 기동 (이미 8080 점유 시 거부) ──────────────────────────────
# 검증은 "방금 빌드한 깨끗한 서버"에만 요청해야 한다.
if lsof -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "❌ 이미 8080 포트가 사용 중입니다. (lsof -i:8080 으로 확인 후 종료)" >&2
    exit 1
fi

echo "서버 기동 중... (로그: $SERVER_LOG)"
: > "$SERVER_LOG"
# gradlew 는 프로젝트 루트에서 실행해야 한다(상대경로로 wrapper 를 찾음).
( cd "$PROJECT_ROOT" && ./gradlew bootRun ) > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
    echo "서버 종료 중 (pid: $SERVER_PID)..."
    kill "$SERVER_PID" 2>/dev/null || true
    # gradlew 가 띄운 자식(JVM)이 8080 을 잡고 있으면 함께 정리한다.
    lsof -ti:8080 2>/dev/null | xargs kill 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Spring Boot 기동 완료 신호를 로그에서 기다린다.
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
sleep 2   # 컨텍스트/툴 빈 초기화 여유
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

    # 이 요청이 만든 로그 구간만 추출(logback CONSOLE 패턴 'HH:MM:SS LEVEL ...'만 남김).
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
