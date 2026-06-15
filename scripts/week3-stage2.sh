#!/usr/bin/env bash
# Round 3 — 2단계 라이브 10턴 실험 스크립트.
#
# 사용법:
#   1) ChatMemoryConfig.MAX_MESSAGES 를 원하는 값(예: 20 / 2 / Integer.MAX_VALUE)으로 수정
#   2) 별도 터미널에서 ./gradlew bootRun
#   3) 본 스크립트 실행:
#        ./scripts/week3-stage2.sh stage2-a
#      ('stage2-a' 는 X-Session-Id 이자 결과 디렉토리 이름. 실험별로 다른 값을 쓸 것)
#
# 결과:
#   experiments/stage2/<session-id>/
#     ├── turn-01.response.json   (LLM 응답 본문)
#     ├── turn-01.memory.json     (해당 턴 직후 Memory 상태)
#     ├── ...
#     ├── turn-10.response.json
#     ├── turn-10.memory.json
#     └── summary.txt             (지시 대명사 해결을 위한 사람 판정용 요약)
#
# 입력 토큰 / 응답 시간은 bootRun 로그의 [PerformanceLoggingAdvisor] 줄을 별도로 grep 해서 정리한다.
#   예: tail -n 200 bootRun.log | grep PerformanceLoggingAdvisor | awk -F'inputTokens=' '{print $2}'

set -euo pipefail

# macOS tr/head 의 멀티바이트 UTF-8 처리 문제를 막기 위해 로케일 명시.
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

readonly SESSION_ID="${1:?사용법: $0 <session-id> (예: stage2-a)}"
readonly HOST="${HOST:-http://localhost:8080}"
readonly OUT_DIR="experiments/stage2/${SESSION_ID}"

mkdir -p "${OUT_DIR}"

# 10턴 시퀀스 — 가이드 docs/notion/Round 3_Quest.md 의 시퀀스를 그대로 사용
TURNS=(
  "2024-1234 배달 상황 알려주세요"
  "그거 몇 분 남았어요?"
  "2024-1235 주문도 있는데 메뉴 뭐였죠?"
  "아 그 버거 세트"
  "2024-1234 취소 가능해요?"
  "그럼 1235는 취소되죠?"
  "그거 취소해주세요"
  "아까 1234는 언제 도착해요?"
  "그 주문 라이더 위치 다시 확인"
  "요약해 주세요 지금까지 제가 뭘 물어봤는지"
)

echo "[stage2] session=${SESSION_ID} 출력=${OUT_DIR}"

# 이전 세션 데이터를 깨끗이 비운다 (재실행 시 이전 윈도우 영향 차단)
curl -s -X DELETE "${HOST}/api/v1/session/${SESSION_ID}" >/dev/null || true

for i in "${!TURNS[@]}"; do
  turn=$(printf "%02d" $((i + 1)))
  msg="${TURNS[$i]}"
  echo "[stage2] turn-${turn}  USER: ${msg}"

  # 요청 페이로드를 jq로 안전하게 만든다 (메시지에 따옴표/특수문자가 들어와도 깨지지 않음)
  payload=$(jq -nc --arg m "${msg}" '{message:$m}')

  # 응답 본문 저장
  curl -s -X POST "${HOST}/api/v1/assistant" \
    -H "Content-Type: application/json" \
    -H "X-Session-Id: ${SESSION_ID}" \
    -d "${payload}" \
    -o "${OUT_DIR}/turn-${turn}.response.txt"

  # 해당 턴 직후 Memory 상태 저장 (USER + ASSISTANT 가 들어가 있어야 함)
  curl -s "${HOST}/api/v1/session/${SESSION_ID}/messages" \
    | jq '.' > "${OUT_DIR}/turn-${turn}.memory.json"

  # 사람이 빨리 훑어볼 수 있는 미리보기: 첫 줄만 150자.
  # 한국어 UTF-8을 안전하게 자르기 위해 awk + substr 사용 (tr/head -c 회피).
  resp_preview=$(awk 'NR==1 { print substr($0, 1, 150); exit }' "${OUT_DIR}/turn-${turn}.response.txt" 2>/dev/null || echo "(preview unavailable)")
  echo "  → ${resp_preview}"
done

# 사람 판정용 요약 파일 — 지시 대명사 해결 성공/실패는 사람이 직접 채점한다.
{
  echo "# Stage 2 — session=${SESSION_ID}"
  echo
  echo "각 턴의 응답에서 다음을 사람이 판정해 README 표를 채운다:"
  echo "  - 턴 7  '그거' → 1235 로 해석되어 cancelOrder(2024-1235) 호출되었는가?"
  echo "  - 턴 8  '아까 1234' → 1234 로 해석되어 적절한 응답을 받았는가?"
  echo "  - 턴 9  '그 주문' → 1234 로 해석되어 라이더 위치를 답했는가?"
  echo "  - 턴 10 요약 → 1234와 1235 모두 포함된 요약인가? 혹은 한쪽이 누락되었는가?"
  echo
  echo "bootRun.log 에서 [PerformanceLoggingAdvisor] 줄을 grep 해 평균 입력 토큰을 계산:"
  echo "  grep PerformanceLoggingAdvisor bootRun.log | tail -10 | \\"
  echo "    awk -F'inputTokens=' '{print \$2}' | awk '{print \$1}' | \\"
  echo "    awk '{s+=\$1; n++} END {if(n)print \"avg=\"s/n\" n=\"n}'"
} > "${OUT_DIR}/summary.txt"

echo
echo "[stage2] 완료. 결과: ${OUT_DIR}/"
echo "[stage2] 다음 단계:"
echo "  1) ${OUT_DIR}/summary.txt 의 판정 항목을 사람이 직접 확인"
echo "  2) bootRun 로그의 [PerformanceLoggingAdvisor] 줄로 입력 토큰 평균 계산"
echo "  3) README 2단계 표의 placeholder 채우기"