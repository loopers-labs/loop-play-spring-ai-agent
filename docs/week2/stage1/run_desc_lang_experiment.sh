#!/usr/bin/env bash
# =============================================================================
# Tool description 한국어 vs 영어 — 측정 실행 스크립트
#
# 매 트라이얼마다 서버를 재시작한다. 이렇게 하면:
#   1) cancelOrder가 바꾼 인메모리 주문 상태가 트라이얼마다 리셋된다(정답 유지).
#   2) Ollama KV/프리픽스 캐시가 콜드 상태로 출발한다(토큰·시간 공정 비교).
#   3) 트라이얼마다 server.log가 따로 생겨 [Tool]/[LLM]/[PERF]가 1:1로 매칭된다.
#
# 사용법:
#   1) ko 브랜치를 현재 브랜치로 두고(=baseline), en 브랜치를 미리 만들어 둔다.
#   2) CASES / N 등 아래 [결정 필요] 값을 채운다.
#   3) 그냥 실행: ./docs/week2/stage1/run_desc_lang_experiment.sh
#      - 미커밋 변경은 시작 시 1회 stash, 종료 시 1회 pop으로 자동 복원된다.
#      - ko → en 브랜치를 스크립트가 직접 checkout한다.
#
# 사전: jq, ollama serve(모델 pull 완료), 포트 8080 비어 있을 것.
# 주의(CLAUDE.md): 기동 확인은 "Started" 로그로만. LLM 엔드포인트 ping 금지.
# =============================================================================
set -euo pipefail

# ──────────────────────── [결정 필요] 채울 값 ────────────────────────
N=5                                              # 트라이얼 반복 횟수 (확정: 5)
MODEL_EXPECTED="qwen2.5"                          # checkout 후 활성 모델 가드 (확정)
TEMP_EXPECTED="0.3"                               # temperature 가드 (확정)
KO_BRANCH="week2/feature01_v6_branch"             # 한국어 baseline (확정)
EN_BRANCH="week2/feature01_v6_tool_desc_en"       # 영어판 브랜치 (ko에서 분기, description만 영어)

# 케이스: "caseId|사용자 메시지(한국어)|기대 도구"
#   기대 도구: getOrderDetail | getDeliveryStatus | cancelOrder | none(미발동)
#   확정: run_scenarios.sh의 5종(c1~c5, 이전 실험과 동일 — 메시지 원문 유지)
#       + FP(오발동) 측정용 도구 미발동 2종(n1~n2).
#   메시지를 수정하면 정답표(문서 §2-3)의 기대 도구·orderId·응답 핵심 사실도 함께 갱신할 것.
CASES=(
  "c1|주문번호 2024-1234 배달 어디쯤에 있어요?|getDeliveryStatus"
  "c2|주문번호 2024-1234 어떤 메뉴 주문했어요?|getOrderDetail"
  "c3|주문번호 2024-1235 방금 시킨 건데 취소해주세요|cancelOrder"
  "c4|주문번호 2024-1236 취소해주세요|cancelOrder"
  "c5|주문번호 2099-9999 배달 어디예요?|getDeliveryStatus"
  "n1|환불 정책이 어떻게 되나요?|none"
  "n2|내 주문 어떻게 됐어요?|none"
)
# ───────────────────────────── 여기까지 ─────────────────────────────

PORT=8080
URL="http://localhost:${PORT}/api/v1/assistant"
ROOT="$(git rev-parse --show-toplevel)"
RUN_ID="$(date +%Y%m%d_%H%M%S)"
OUT_ROOT="${ROOT}/docs/week2/stage1/responses/desc_lang/${RUN_ID}"
YML="${ROOT}/src/main/resources/application.yml"

command -v jq >/dev/null 2>&1 || { echo "‼ jq 필요: brew install jq"; exit 1; }

ORIG_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
STASH_MSG="desc-lang-exp-autostash-${RUN_ID}"
STASHED=0

# ── 종료 시 항상: 서버 kill → 원래 브랜치 복귀 → stash pop ──
cleanup() {
  pkill -f 'bootRun' 2>/dev/null || true
  if lsof -ti:"${PORT}" >/dev/null 2>&1; then kill "$(lsof -ti:"${PORT}")" 2>/dev/null || true; fi

  git checkout "${ORIG_BRANCH}" 2>/dev/null || echo "‼ 원래 브랜치(${ORIG_BRANCH}) 복귀 실패 — 수동 확인" >&2

  if [[ "${STASHED}" == "1" ]]; then
    echo "[stash] 복원 시도: ${STASH_MSG}"
    if ! git stash pop; then
      echo "‼ stash pop 충돌 — 워킹트리 수동 정리 필요. 'git stash list'에 ${STASH_MSG} 보존됨." >&2
    fi
  fi
}
trap cleanup EXIT

# ── 0. 사전 가드 ──
git rev-parse --verify "${EN_BRANCH}" >/dev/null 2>&1 \
  || { echo "‼ en 브랜치 없음: ${EN_BRANCH}"; echo "  → ko에서 분기해 description만 번역 후 커밋: git checkout -b ${EN_BRANCH} ${KO_BRANCH}"; exit 1; }

# ── 1. 미커밋 변경 1회 stash (-u: untracked 포함) ──
if [[ -n "$(git status --porcelain)" ]]; then
  echo "[stash] 미커밋 변경 보관: ${STASH_MSG}"
  git stash push -u -m "${STASH_MSG}"
  STASHED=1
fi

# ── 서버 기동 대기 (Started 로그로만 확인) ──
wait_started() {  # $1: logfile
  echo -n "    기동 대기"
  for _ in $(seq 1 180); do
    if grep -q "Started .* in .* seconds" "$1" 2>/dev/null; then echo " ✓"; return 0; fi
    if grep -qiE "BUILD FAILED|APPLICATION FAILED TO START" "$1" 2>/dev/null; then echo " ✗ 기동 실패"; return 1; fi
    sleep 1; echo -n "."
  done
  echo " ✗ 타임아웃"; return 1
}

# ── 서버 종료 + 포트 해제 대기 ──
stop_server() {  # $1: gradle pid
  kill "$1" 2>/dev/null || true
  if lsof -ti:"${PORT}" >/dev/null 2>&1; then kill "$(lsof -ti:"${PORT}")" 2>/dev/null || true; fi
  for _ in $(seq 1 30); do lsof -ti:"${PORT}" >/dev/null 2>&1 || return 0; sleep 1; done
}

# ── 한 언어 블록 실행 ──
run_block() {  # $1: lang(ko/en)  $2: branch
  local lang="$1" branch="$2"
  echo "════════ [${lang}] ${branch} ════════"
  git checkout "${branch}"

  # checkout 후 모델/temperature 가드 (stash로 config가 바뀌었어도 차단)
  local active_model active_temp
  active_model="$(grep -E '^[[:space:]]*model:' "${YML}" | head -1 | awk '{print $2}')"
  active_temp="$(grep -E '^[[:space:]]*temperature:' "${YML}" | head -1 | awk '{print $2}')"
  [[ "${active_model}" == "${MODEL_EXPECTED}" ]] || { echo "‼ 모델 불일치: '${active_model}' (기대 '${MODEL_EXPECTED}')"; exit 1; }
  [[ "${active_temp}"  == "${TEMP_EXPECTED}"  ]] || { echo "‼ temperature 불일치: '${active_temp}' (기대 '${TEMP_EXPECTED}')"; exit 1; }

  local out="${OUT_ROOT}/${lang}"
  mkdir -p "${out}"

  local spec cid msg expected n slog json tmp meta http sec gpid
  for spec in "${CASES[@]}"; do
    IFS='|' read -r cid msg expected <<< "${spec}"
    for n in $(seq 1 "${N}"); do
      slog="${out}/${cid}_trial_${n}.server.log"
      json="${out}/${cid}_trial_${n}.json"

      # 서버 기동 (트라이얼마다 콜드 스타트)
      ./gradlew bootRun > "${slog}" 2>&1 &
      gpid=$!
      wait_started "${slog}" || { echo "‼ ${lang}/${cid} #${n} 기동 실패 — ${slog} 확인"; stop_server "${gpid}"; exit 1; }

      # 호출 1건
      tmp="$(mktemp)"
      meta="$(curl -s -X POST "${URL}" -H 'Content-Type: application/json' \
                -d "$(jq -n --arg m "${msg}" '{message:$m}')" \
                -w '%{http_code} %{time_total}' -o "${tmp}")"
      http="$(echo "${meta}" | awk '{print $1}')"
      sec="$(echo "${meta}"  | awk '{print $2}')"

      jq -n \
        --arg lang "${lang}" --arg cid "${cid}" --argjson trial "${n}" \
        --arg msg "${msg}" --arg expected "${expected}" --arg runId "${RUN_ID}" \
        --arg body "$(cat "${tmp}")" --argjson http "${http}" --argjson sec "${sec}" \
        '{runId:$runId, lang:$lang, caseId:$cid, trial:$trial, expectedTool:$expected,
          request:{message:$msg},
          response:{body:$body, httpCode:$http, elapsedSeconds:$sec}}' > "${json}"
      rm -f "${tmp}"

      echo "    [${lang}/${cid} #${n}] http=${http} ${sec}s → ${json##*/}"

      # 로그 flush 대기: 요청마다 [PERF]가 마지막에 1줄 찍힌다.
      # curl이 응답을 받아도 gradle의 stdout 버퍼가 flush되기 전에 kill하면
      # [LLM #2]/[PERF](토큰 집계)가 잘린다. [PERF]가 보일 때까지(최대 8초) 대기한다.
      for _ in $(seq 1 16); do grep -q '\[PERF\]' "${slog}" && break; sleep 0.5; done
      grep -q '\[PERF\]' "${slog}" || echo "    ⚠ [PERF] 미검출(토큰 누락 가능) — ${slog##*/}"

      # 서버 종료 → 다음 트라이얼은 콜드
      stop_server "${gpid}"
    done
  done
}

# ── 2. 실행: ko → en (순서 고정, 두 블록 동일 케이스 순서) ──
echo "RUN_ID=${RUN_ID}  N=${N}  MODEL=${MODEL_EXPECTED}  케이스=${#CASES[@]}개"
echo "출력: ${OUT_ROOT}/{ko,en}/"
run_block ko "${KO_BRANCH}"
run_block en "${EN_BRANCH}"

echo "════════ 완료 ════════"
echo "토큰 추출 예: grep '\\[PERF\\]' ${OUT_ROOT}/ko/c1_trial_1.server.log"
echo "도구 추출 예: grep '\\[Tool\\]' ${OUT_ROOT}/ko/c1_trial_1.server.log"
echo "시간 추출 예: jq -r '.response.elapsedSeconds' ${OUT_ROOT}/ko/c1_trial_*.json"
