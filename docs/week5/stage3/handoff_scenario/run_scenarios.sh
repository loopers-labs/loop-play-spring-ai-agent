#!/bin/bash
# 5주차 3단계 — HandoffDetector 상담원 전환 시나리오 러너.
#
# cases.json 의 각 case.message 를 순서대로 POST /api/v1/assistant 에 보낸다.
# HandoffDetector.detect() 가 handoff==true 로 판별하면 컨트롤러가 LLM 호출 전에 전환 안내를
# 반환하므로 '[LLM #'·'[PERF]' 로그가 없고 응답 시간이 수십 ms 수준이다(전환 1·2·3·6).
# 규칙 우회로 미탐지되면(4·5) 선검사를 통과해 LLM 까지 흘러가고, 일반 트래픽(7)도 LLM 응답을 받는다.
#   전환 증거   : 로그에 '[Handoff] reason=<expected_trigger>' 있음
#   LLM 호출 증거: 로그에 '[LLM #' 또는 '[PERF]' 있음 (전환되면 없어야 함)
#
# 사용법(repo 루트 기준):
#   MODEL=qwen2.5 bash docs/week5/stage3/handoff_scenario/run_scenarios.sh
#
# 전제: jq 설치, PgVector 기동 + 지식 적재, Ollama 모델 pull. 빌드 조건은 README/report 참고.
# 산출물: <이 디렉터리>/responses/scenarioN.json, scenarioN_logs.log, evidence.tsv (gitignore)

set -euo pipefail

trap 'echo "❌ 중단됨 (exit=$?) — 서버 연결 또는 직전 [Scenario] 단계를 확인하세요" >&2' ERR

MODEL="${MODEL:-qwen2.5}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASES="$SCRIPT_DIR/cases.json"

command -v jq >/dev/null 2>&1 || { echo "jq required. brew install jq"; exit 1; }
[[ -f "$CASES" ]] || { echo "❌ cases 파일이 없습니다: $CASES" >&2; exit 1; }

OUT="$SCRIPT_DIR/responses"
EVIDENCE="$OUT/evidence.tsv"

PROJECT_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$PROJECT_ROOT" ]] || PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
[[ -x "$PROJECT_ROOT/gradlew" ]] || { echo "❌ gradlew 를 찾지 못했습니다: $PROJECT_ROOT/gradlew" >&2; exit 1; }

URL="http://localhost:8080/api/v1/assistant"

mkdir -p "$OUT"
SERVER_LOG="$OUT/server.log"
echo "Model:       $MODEL"
echo "Cases:       $CASES"
echo "ProjectRoot: $PROJECT_ROOT"
echo "Output:      $OUT"
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

printf 'scenario\texpected_trigger\thandoff_log\tllm_call\telapsed_ms\thttp_code\n' > "$EVIDENCE"

# ── 한 시나리오 실행 + 응답/로그/증거 저장 ──────────────────────────
call() {
    local num="$1"
    local message="$2"
    local expected_trigger="$3"

    local outfile="$OUT/scenario${num}.json"
    local logfile="$OUT/scenario${num}_logs.log"

    echo "[Scenario $num] (len=${#message}) trigger기대=$expected_trigger"

    local log_before
    log_before="$(wc -l < "$SERVER_LOG")"

    local tmpfile meta body http_code elapsed elapsed_ms
    tmpfile="$(mktemp)"
    meta="$(curl -s -X POST "$URL" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg msg "$message" '{message: $msg}')" \
        -w '%{http_code} %{time_total}' \
        -o "$tmpfile")"
    body="$(cat "$tmpfile")"; rm -f "$tmpfile"
    http_code="$(echo "$meta" | awk '{print $1}')"
    elapsed="$(echo "$meta" | awk '{print $2}')"
    elapsed_ms="$(awk -v t="$elapsed" 'BEGIN{printf "%.0f", t*1000}')"

    # logback flush 경쟁을 피해 이 요청의 로그가 모두 기록되도록 settle.
    sleep 1

    jq -n \
        --argjson scenario "$num" \
        --arg model "$MODEL" \
        --arg message "$message" \
        --arg expected_trigger "$expected_trigger" \
        --arg body "$body" \
        --argjson httpCode "$http_code" \
        --argjson elapsed "$elapsed" \
        '{
            scenario: $scenario,
            model: $model,
            request: { messageLength: ($message | length), expectedTrigger: $expected_trigger },
            response: { body: $body, httpCode: $httpCode, elapsedSeconds: $elapsed }
        }' > "$outfile"

    # 이 요청이 만든 로그 구간만 추출.
    tail -n "+$((log_before + 1))" "$SERVER_LOG" > "$logfile" || true

    # 증거 추출: 전환 로그(기대 트리거 일치) / LLM 호출 로그 유무.
    # NONE 케이스는 '[Handoff] reason=NONE' 가 찍히지 않으므로 handoff_log=X 가 맞다.
    local handoff_log="X" llm_call="X"
    grep -qE "\[Handoff\] reason=${expected_trigger}" "$logfile" && handoff_log="O"
    grep -qE "\[LLM #|\[PERF\]" "$logfile" && llm_call="O"

    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$num" "$expected_trigger" "$handoff_log" "$llm_call" "$elapsed_ms" "$http_code" >> "$EVIDENCE"
    echo "  → handoff_log=$handoff_log llm_call=$llm_call ${elapsed_ms}ms http=$http_code"
}

# ── cases 순서대로 실행 ────────────────────────────────────────────
count="$(jq '.cases | length' "$CASES")"
[[ "$count" -gt 0 ]] || { echo "❌ cases 가 비어 있습니다: $CASES" >&2; exit 1; }

for i in $(seq 0 $((count - 1))); do
    num="$(jq -r ".cases[$i].scenario" "$CASES")"
    msg="$(jq -r ".cases[$i].message" "$CASES")"
    trigger="$(jq -r ".cases[$i].expected_trigger" "$CASES")"
    call "$num" "$msg" "$trigger"
done

echo "---"
echo "Done. 응답/로그/증거가 $OUT 에 저장되었습니다."
echo "증거 요약(evidence.tsv):"
column -t -s $'\t' "$EVIDENCE"
echo ""
echo "판정 기준:"
echo "  전환(1·2·3·6): handoff_log=O 이고 llm_call=X (LLM 호출 전 전환, 수십 ms)"
echo "  우회(4·5)·일반(7): handoff_log=X 이고 llm_call=O (선검사 통과 → LLM 응답, 수 초)"
echo "  전환 응답 body 에는 연결 번호 '1600-0987' 이 포함되어야 한다(육안 확인)."
