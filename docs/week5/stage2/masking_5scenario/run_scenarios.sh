#!/bin/bash
# 5주차 2단계 — OutputGuardrail/SensitiveDataMasker 5종 시나리오 러너.
#
# cases.json 의 각 case.message 를 순서대로 POST /api/v1/assistant 에 보낸다.
# 입력 가드(order=5)는 5종 모두 통과하고, LLM 왕복 후 출력 가드(order=50)가 응답을 검사한다.
# 요청별 응답 본문과 서버 로그 구간을 저장하고, 출력 가드 발동 증거를 함께 추출한다.
#   마스킹 증거 : 로그에 '[OutputGuardrail] 응답 치환 — reason=SENSITIVE_MASKED' 있음
#   유출차단 증거: 로그에 '[OutputGuardrail] 응답 치환 — reason=PROMPT_LEAK' 있음
#
# 주의: 출력 가드는 'LLM이 실제로 민감값/내부 섹션을 응답에 재현'해야 발동하므로 비결정적이다.
#       발동하지 않은 경우(=LLM이 애초에 노출하지 않음)도 다층 방어상 정상이며, 응답 body로 함께 판정한다.
#
# 사용법(repo 루트 기준):
#   MODEL=qwen2.5 bash docs/week5/stage2/masking_5scenario/run_scenarios.sh
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

printf 'scenario\texpected_reason\tmasked_log\tleak_log\thttp_code\n' > "$EVIDENCE"

# ── 한 시나리오 실행 + 응답/로그/증거 저장 ──────────────────────────
call() {
    local num="$1"
    local message="$2"
    local expected_reason="$3"
    local outfile="$OUT/scenario${num}.json"
    local logfile="$OUT/scenario${num}_logs.log"

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

    # logback flush 경쟁을 피해 이 요청의 로그가 모두 기록되도록 settle.
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

    # 증거 추출: 출력 가드 치환 로그(reason별) 유무.
    local masked_log="X" leak_log="X"
    grep -qE "\[OutputGuardrail\] 응답 치환 — reason=SENSITIVE_MASKED" "$logfile" && masked_log="O"
    grep -qE "\[OutputGuardrail\] 응답 치환 — reason=PROMPT_LEAK" "$logfile" && leak_log="O"

    printf '%s\t%s\t%s\t%s\t%s\n' "$num" "$expected_reason" "$masked_log" "$leak_log" "$http_code" >> "$EVIDENCE"
    echo "  → masked_log=$masked_log leak_log=$leak_log http=$http_code (${elapsed}s)"
}

# ── cases 순서대로 실행 ────────────────────────────────────────────
count="$(jq '.cases | length' "$CASES")"
[[ "$count" -gt 0 ]] || { echo "❌ cases 가 비어 있습니다: $CASES" >&2; exit 1; }

for i in $(seq 0 $((count - 1))); do
    num="$(jq -r ".cases[$i].scenario" "$CASES")"
    msg="$(jq -r ".cases[$i].message" "$CASES")"
    reason="$(jq -r ".cases[$i].expected_reason" "$CASES")"
    call "$num" "$msg" "$reason"
done

echo "---"
echo "Done. 응답/로그/증거가 $OUT 에 저장되었습니다."
echo "증거 요약(evidence.tsv):"
column -t -s $'\t' "$EVIDENCE"
echo ""
echo "판정 기준:"
echo "  마스킹(1~4): masked_log=O 또는 응답 body에 마스킹 형식(010-****-…, [주소 비공개]) 노출"
echo "  유출차단(5): leak_log=O  또는 응답 body가 LEAK_FALLBACK 안내문구"
echo "  (출력 가드는 LLM이 민감값/내부 섹션을 재현해야 발동 — 미발동 시 응답 body로 다층방어 정상 여부 판정)"
