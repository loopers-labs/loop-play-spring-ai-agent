#!/usr/bin/env python3
"""시나리오 응답/로그를 기대값(cases 파일)과 대조해 pass/fail을 '코드로' 판정하고
결과를 <cases 파일 디렉터리>/responses/<cases_stem>/results.json 으로 출력한다.

사람이 읽는 2축(동작/내용) 검증 리포트는 이 스크립트가 만들지 않는다.
→ 'llm-scenario-verification' 스킬로 작성한다
  (이 스크립트의 results.json + 원본 scenarioN.json/로그를 근거로).

이 스크립트는 공유 위치(docs/verification)에 단벌로 둔다. 시나리오는 각 스테이지의
cases 파일에 있고, --cases 로 경로를 넘긴다. 출력은 cases 파일 옆에 떨어진다
(run_scenarios.sh 의 OUT 과 일치).

사용법:
    python3 docs/verification/evaluate.py --cases docs/week2/stage1/toolcalling_verification/cases.json
    MODEL=qwen2.5 python3 docs/verification/evaluate.py --cases <path>/cases.json

종료코드:
    0 = 전부 통과, 1 = 하나라도 실패(또는 입력 누락)
"""

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path


def load_response(out_dir: Path, scenario: int):
    """쉘이 저장한 scenarioN.json 을 읽는다. response.body 는 문자열(JSON일 수도)."""
    f = out_dir / f"scenario{scenario}.json"
    if not f.exists():
        return None
    data = json.loads(f.read_text(encoding="utf-8"))
    return data


def load_logs(out_dir: Path, scenario: int) -> str:
    f = out_dir / f"scenario{scenario}_logs.log"
    return f.read_text(encoding="utf-8", errors="replace") if f.exists() else ""


def _tokenize_expr(expr: str):
    """response_match 식을 토큰열로 분해한다.
    연산자: && (AND), || (OR), 괄호 ( ). 그 외 텍스트는 부분문자열 토큰(TEXT)."""
    toks, buf, i, n = [], [], 0, len(expr)

    def flush():
        s = "".join(buf).strip()
        if s:
            toks.append(("TEXT", s))
        buf.clear()

    while i < n:
        if expr.startswith("&&", i):
            flush(); toks.append(("AND", None)); i += 2
        elif expr.startswith("||", i):
            flush(); toks.append(("OR", None)); i += 2
        elif expr[i] == "(":
            flush(); toks.append(("LP", None)); i += 1
        elif expr[i] == ")":
            flush(); toks.append(("RP", None)); i += 1
        else:
            buf.append(expr[i]); i += 1
    flush()
    return toks


def expr_tokens(expr: str):
    """식에 쓰인 부분문자열 토큰만 (리포트 표시용) 추출."""
    return [t for k, t in _tokenize_expr(expr) if k == "TEXT"]


def eval_match(expr: str, body: str) -> bool:
    """부분문자열 토큰을 && / || / () 로 조합한 식을 body에 대해 평가한다.
    TEXT 토큰의 참 = (토큰이 body의 부분문자열인가). 우선순위: () > && > ||.
    예) '취소 && (되었 || 완료)' → body에 '취소'가 있고 ('되었' 또는 '완료')가 있으면 참."""
    toks = _tokenize_expr(expr)
    pos = 0

    def peek():
        return toks[pos][0] if pos < len(toks) else None

    def advance():
        nonlocal pos
        pos += 1

    def parse_or():
        v = parse_and()
        while peek() == "OR":
            advance(); v = parse_and() or v
        return v

    def parse_and():
        v = parse_factor()
        while peek() == "AND":
            advance(); v = parse_factor() and v
        return v

    def parse_factor():
        nonlocal pos
        if peek() == "LP":
            advance(); v = parse_or()
            if peek() == "RP":
                advance()
            return v
        if peek() == "TEXT":
            tok = toks[pos][1]; advance()
            return tok in body
        advance()  # 예기치 못한 토큰은 무시
        return False

    return parse_or()


def check_case(case: dict, resp: dict, logs: str):
    """단언들을 돌려 (passed, checks) 반환. checks = [{name, ok, detail}]."""
    exp = case["expected"]
    checks = []

    def record(name, ok, detail=""):
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    http_code = resp["response"]["httpCode"]
    body = resp["response"]["body"] or ""

    # 1) HTTP 상태코드
    if "http_code" in exp:
        record(f"HTTP == {exp['http_code']}", http_code == exp["http_code"],
               f"실제: {http_code}")

    # 2) 응답 본문 매칭 (response_match: && / || / () 부분문자열 식)
    #    && = 두 토큰이 (인접하지 않아도) 응답 어딘가에 모두 있으면 참
    #    || = 둘 중 하나만, () = 우선순위
    if "response_match" in exp:
        expr = exp["response_match"]
        detail = " ".join(f"{t}{'✓' if t in body else '✗'}"
                          for t in expr_tokens(expr))
        record(f"응답이 식 만족: {expr}", eval_match(expr, body), detail)

    # 3) 로그에 툴 호출 흔적
    if "log_tool" in exp:
        tool = exp["log_tool"]
        record(f"로그에 '{tool}' 호출", tool in logs,
               "" if tool in logs else "로그에서 못 찾음")

    # 4) 로그에 있으면 안 되는 키워드
    for kw in exp.get("log_absent", []):
        record(f"로그에 '{kw}' 없음", kw not in logs,
               "발견됨" if kw in logs else "")

    passed = all(c["ok"] for c in checks)
    return passed, checks


def status_icon(ok: bool) -> str:
    return "✅" if ok else "❌"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("MODEL", "qwen2.5"))
    ap.add_argument("--cases", default="cases.json",
                    help="cases 파일 경로 (상대경로는 CWD 기준)")
    ap.add_argument("--base", default=None,
                    help="출력 디렉터리 경로 (기본: <cases 파일 디렉터리>/responses/<cases_stem>)")
    args = ap.parse_args()

    # cases 경로는 CWD 기준으로 해석한다(스크립트는 공유 위치에 있으므로).
    cases_path = Path(args.cases).resolve()

    # 출력은 cases 파일 옆에 떨어진다: <cases_dir>/responses/<cases_stem>/
    # run_scenarios.sh 의 OUT 과 일치시켜, 러너 산출물을 그대로 읽는다.
    out_dir = Path(args.base).resolve() if args.base else cases_path.parent / "responses" / cases_path.stem

    cases = json.loads(cases_path.read_text(encoding="utf-8"))["cases"]

    results = []
    for case in cases:
        n = case["scenario"]
        resp = load_response(out_dir, n)
        entry = {"scenario": n, "name": case["name"]}
        if resp is None:
            entry.update({"passed": False, "missing": True, "checks": []})
        else:
            passed, checks = check_case(case, resp, load_logs(out_dir, n))
            entry.update({"passed": passed, "missing": False, "checks": checks})
        results.append(entry)

    passed_n = sum(r["passed"] for r in results)
    total = len(results)

    # 코드 베이스 판정 결과를 JSON으로 저장 — 스킬/CI가 읽는 단일 근거.
    out_dir.mkdir(parents=True, exist_ok=True)
    results_path = out_dir / "results.json"
    results_path.write_text(json.dumps({
        "model": args.model,
        "generated": f"{datetime.now():%Y-%m-%d %H:%M}",
        "summary": {"passed": passed_n, "total": total},
        "results": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    # 사람용 요약 (stdout)
    print(f"[{args.model}] 코드 베이스 판정 결과")
    for r in results:
        tag = " (응답 없음)" if r.get("missing") else ""
        print(f"  S{r['scenario']} {r['name']}  {status_icon(r['passed'])}{tag}")
    print(f"결과: {passed_n}/{total} 통과")
    print(f"results: {results_path}")
    print("→ 2축(동작/내용) 검증 리포트는 'llm-scenario-verification' 스킬로 작성하세요.")
    sys.exit(0 if passed_n == total else 1)


if __name__ == "__main__":
    main()
