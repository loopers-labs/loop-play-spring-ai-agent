#!/bin/bash
# 1단계 Tool Calling 시나리오 5종 실행 스크립트
#
# 사용법:
#   MODEL=llama3.1 ./run_scenarios.sh
#   MODEL=qwen2.5  ./run_scenarios.sh
#
# 응답은 docs/week2/stage1/responses/$MODEL/scenarioN.json 에 저장된다.
#
# 사전 요구: jq 설치 (`brew install jq`)

set -euo pipefail

MODEL="${MODEL:-qwen2.5}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/responses/$MODEL"
URL="http://localhost:8080/api/v1/assistant"

# 서버 콘솔 로그 파일. 미지정 시 활성 Gradle 데몬 out.log를 자동 탐지한다.
# (logback CONSOLE 출력이 bootRun을 통해 데몬 out.log에 기록된다.)
SERVER_LOG="${SERVER_LOG:-$(ls -t "$HOME"/.gradle/daemon/*/daemon-*.out.log 2>/dev/null | head -1)}"

command -v jq >/dev/null 2>&1 || { echo "jq required. brew install jq"; exit 1; }

mkdir -p "$OUT"
echo "Model: $MODEL"
echo "Output: $OUT"
echo "ServerLog: ${SERVER_LOG:-(없음)}"
echo "---"

# 실행 중 추가된 줄만 추출하기 위해 시작 지점(줄 수)을 기록한다.
LOG_START_LINE=""
if [[ -n "${SERVER_LOG:-}" && -f "$SERVER_LOG" ]]; then
    LOG_START_LINE="$(wc -l < "$SERVER_LOG")"
else
    echo "  ⚠ 서버 로그 파일을 찾지 못했습니다. SERVER_LOG=<경로> 로 지정하세요."
fi

call() {
    local num="$1"
    local message="$2"
    local outfile="$OUT/scenario${num}.json"
    local req_time
    req_time="$(date -Iseconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"

    echo "[Scenario $num] $message"

    # 본문은 임시 파일에, 메타데이터(HTTP 코드·소요 시간)는 stdout에 받기
    local tmpfile
    tmpfile="$(mktemp)"
    local meta
    meta="$(curl -s -X POST "$URL" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg msg "$message" '{message: $msg}')" \
        -w '%{http_code} %{time_total}' \
        -o "$tmpfile")"

    local body http_code elapsed
    body="$(cat "$tmpfile")"
    rm -f "$tmpfile"
    http_code="$(echo "$meta" | awk '{print $1}')"
    elapsed="$(echo "$meta" | awk '{print $2}')"

    # JSON 조립
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
            request: {
                message: $message,
                time: $time
            },
            response: {
                body: $body,
                httpCode: $httpCode,
                elapsedSeconds: $elapsed
            }
        }' > "$outfile"

    echo "  → saved: $outfile (http=$http_code, ${elapsed}s)"
    sleep 2   # 서버 로그 매칭을 위해 시나리오 간 간격
}

# 시나리오 1 — getDeliveryStatus (2024-1234, DELIVERING)
call 1 "주문번호 2024-1234 배달 어디쯤에 있어요?"

# 시나리오 2 — getOrderDetail (2024-1234, DELIVERING)
call 2 "주문번호 2024-1234 어떤 메뉴 주문했어요?"

# 시나리오 3 — cancelOrder CREATED (2024-1235)
call 3 "주문번호 2024-1235 방금 시킨 건데 취소해주세요"

# 시나리오 4 — cancelOrder NOT_CANCELABLE (2024-1236, DELIVERED)
call 4 "주문번호 2024-1236 취소해주세요"

# 시나리오 5 — null 처리 (2099-9999, 존재하지 않음)
call 5 "주문번호 2099-9999 배달 어디예요?"

# 서버 콘솔 로그 스냅샷 — 실행 중 추가된 앱 로그만 추출한다.
# logback CONSOLE 패턴(HH:MM:SS LEVEL ...)만 남기고 Gradle 노이즈(ISO 타임스탬프 [DEBUG])는 제외한다.
if [[ -n "${LOG_START_LINE:-}" && -f "$SERVER_LOG" ]]; then
    tail -n "+$((LOG_START_LINE + 1))" "$SERVER_LOG" \
        | grep -E '^[0-9]{2}:[0-9]{2}:[0-9]{2} ' \
        > "$OUT/server_logs.log" || true
    echo "ServerLog 저장: $OUT/server_logs.log ($(wc -l < "$OUT/server_logs.log") lines)"
fi

echo "---"
echo "Done. 5개 시나리오 응답이 $OUT 에 저장되었습니다."
echo ""
echo "예시 쿼리:"
echo "  jq '.response.body' $OUT/scenario1.json"
echo "  jq '.response.elapsedSeconds' $OUT/*.json"
