#!/bin/bash
# 5주차 1단계 — InputGuardrail 5종 시나리오 러너.
#
# cases.json 의 각 case.message 를 순서대로 POST /api/v1/assistant 에 보낸다.
# scenario 4 의 '__GENERATE_LONG__' 플레이스홀더는 5001자(>2000 임계) 문자열로 치환한다.
# 요청별 응답 본문과 서버 로그 구간을 저장하고, "LLM 호출 0(cost-0)" 증거를 함께 추출한다.
#   차단 증거 : 로그에 '[InputGuardrail] 차단' 있음
#   cost-0 증거: 로그에 '[LLM #' · '[PERF]' 없음 (short-circuit 으로 체인 미도달)
#
# 사용법(repo 루트 기준):
#   MODEL=qwen2.5 bash docs/week5/stage1/guardrail_5scenario/run_scenarios.sh
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

printf 'scenario\texpected_reason\tblocked_log\tllm_called_log\thttp_code\n' > "$EVIDENCE"

# ── 한 시나리오 실행 + 응답/로그/증거 저장 ──────────────────────────
call() {
    local num="$1"
    local message="$2"
    local expected_reason="$3"
    local outfile="$OUT/scenario${num}.json"
    local logfile="$OUT/scenario${num}_logs.log"

    # 화면 표시는 길이만(5001자 통째 출력 방지).
    echo "[Scenario $num] (len=${#message}) reason기대=$expected_reason"

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

    # 차단 시나리오는 3~50ms로 끝나 logback이 파일에 flush하기 전에 슬라이스되는 경쟁이 있다.
    # 짧게 settle 해 이 요청의 로그가 server.log 에 모두 기록되도록 한 뒤 슬라이스한다.
    sleep 1

    jq -n \
        --argjson scenario "$num" \
        --arg model "$MODEL" \
        --arg message "$message" \
        --arg expected_reason "$expected_reason" \
        --arg body "$body" \
        --argjson httpCode "$http_code" \
        --argjson elapsed "$elapsed" \
        '{
            scenario: $scenario,
            model: $model,
            request: { messageLength: ($message | length), expectedReason: $expected_reason },
            response: { body: $body, httpCode: $httpCode, elapsedSeconds: $elapsed }
        }' > "$outfile"

    # 이 요청이 만든 로그 구간만 추출.
    tail -n "+$((log_before + 1))" "$SERVER_LOG" > "$logfile" || true

    # 증거 추출: 차단 로그 / LLM 호출 로그 유무.
    # 차단은 advisor('[InputGuardrail] 차단') 또는 컨트롤러 빈입력 선검사('입력 차단') 어느 쪽이든 인정.
    local blocked_log="X" llm_log="X"
    grep -qE "\[InputGuardrail\] 차단|입력 차단" "$logfile" && blocked_log="O"
    grep -Eq "\[LLM #|\[PERF\]" "$logfile" && llm_log="O"

    printf '%s\t%s\t%s\t%s\t%s\n' "$num" "$expected_reason" "$blocked_log" "$llm_log" "$http_code" >> "$EVIDENCE"
    echo "  → blocked_log=$blocked_log llm_called_log=$llm_log http=$http_code (${elapsed}s)"
}

# ── cases 순서대로 실행 (scenario 4 는 5001자 생성) ────────────────
count="$(jq '.cases | length' "$CASES")"
[[ "$count" -gt 0 ]] || { echo "❌ cases 가 비어 있습니다: $CASES" >&2; exit 1; }

# 5001자(>2000) 반복 문자열. "배달 늦어요 " (6자) * 834 = 5004자.
LONG_INPUT="$(printf '배달 늦어요 %.0s' $(seq 1 834))"

for i in $(seq 0 $((count - 1))); do
    num="$(jq -r ".cases[$i].scenario" "$CASES")"
    msg="$(jq -r ".cases[$i].message" "$CASES")"
    reason="$(jq -r ".cases[$i].expected_reason" "$CASES")"
    [[ "$msg" == "__GENERATE_LONG__" ]] && msg="$LONG_INPUT"
    call "$num" "$msg" "$reason"
done

echo "---"
echo "Done. 응답/로그/증거가 $OUT 에 저장되었습니다."
echo "증거 요약(evidence.tsv):"
column -t -s $'\t' "$EVIDENCE"
echo ""
echo "판정 기준:"
echo "  차단(1~4): blocked_log=O AND llm_called_log=X  → LLM 호출 0(cost-0)"
echo "  통과(5)  : blocked_log=X AND llm_called_log=O  → RAG/LLM 정상 응답"
