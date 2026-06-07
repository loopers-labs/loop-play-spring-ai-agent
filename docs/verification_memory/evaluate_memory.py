#!/usr/bin/env python3
"""Chat Memory step 응답을 기대값(cases 파일)과 대조해 pass/fail을 '코드로' 판정하고
결과를 <cases 파일 디렉터리>/responses/<cases_stem>/results.json 으로 출력한다.

run_memory_scenarios.sh 가 저장한 step<N>.json 을 읽는다. 관리 엔드포인트
(GET messages / GET ids / DELETE)의 응답까지 단언할 수 있도록 메모리 전용 체크를 둔다.

사용법:
    python3 docs/verification_memory/evaluate_memory.py --cases docs/week3/stage1/memory_verification/cases.json

종료코드: 0 = 전부 통과, 1 = 하나라도 실패(또는 입력 누락)
"""

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path


def load_step(out_dir: Path, step: int):
    f = out_dir / f"step{step}.json"
    if not f.exists():
        return None
    return json.loads(f.read_text(encoding="utf-8"))


def load_logs(out_dir: Path, step: int) -> str:
    """러너가 저장한 step<N>_logs.log(서버 로그 슬라이스)를 읽는다. 없으면 빈 문자열."""
    f = out_dir / f"step{step}_logs.log"
    return f.read_text(encoding="utf-8", errors="replace") if f.exists() else ""


# ── response_match 식 평가 (evaluate.py 와 동일 규칙: && / || / () 부분문자열) ──
def _tokenize_expr(expr: str):
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


def eval_match(expr: str, body: str) -> bool:
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
        advance()
        return False

    return parse_or()


def parse_messages(body: str):
    """GET messages 본문을 [{type, content}] 배열로 파싱. 실패 시 None."""
    try:
        data = json.loads(body)
        return data if isinstance(data, list) else None
    except (json.JSONDecodeError, TypeError):
        return None


def check_step(case: dict, step_data: dict, logs: str = ""):
    exp = case.get("expected", {})
    checks = []

    def record(name, ok, detail=""):
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    http_code = step_data["response"]["httpCode"]
    body = step_data["response"]["body"] or ""

    if "http_code" in exp:
        record(f"HTTP == {exp['http_code']}", http_code == exp["http_code"],
               f"실제: {http_code}")

    if "response_match" in exp:
        expr = exp["response_match"]
        record(f"응답이 식 만족: {expr}", eval_match(expr, body))

    for kw in exp.get("body_include", []):
        record(f"본문에 '{kw}' 포함", kw in body,
               "" if kw in body else "없음")

    for kw in exp.get("body_absent", []):
        record(f"본문에 '{kw}' 없음", kw not in body,
               "발견됨" if kw in body else "")

    # 메시지 배열 길이/타입 단언
    if "message_count" in exp or "message_min" in exp or "types_include" in exp:
        msgs = parse_messages(body)
        if msgs is None:
            record("메시지 배열 파싱", False, f"JSON 배열 아님: {body[:60]}")
        else:
            if "message_count" in exp:
                record(f"메시지 == {exp['message_count']}건",
                       len(msgs) == exp["message_count"], f"실제: {len(msgs)}건")
            if "message_min" in exp:
                record(f"메시지 >= {exp['message_min']}건",
                       len(msgs) >= exp["message_min"], f"실제: {len(msgs)}건")
            if "types_include" in exp:
                types = {m.get("type") for m in msgs}
                for t in exp["types_include"]:
                    record(f"type '{t}' 존재", t in types,
                           f"실제 타입: {sorted(types)}")

    # 서버 로그(step<N>_logs.log) 기반 tool 호출 단언
    #   response_match는 실제 tool 호출과 few-shot 예시 복사를 구분 못 하므로,
    #   tool 동작 검증은 [Tool] 로그를 직접 확인한다.
    if "log_tool" in exp:
        tool = exp["log_tool"]
        record(f"로그에 tool '{tool}' 호출", tool in logs,
               "" if tool in logs else "로그에서 못 찾음(미호출)")

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
    ap.add_argument("--cases", default="cases.json")
    ap.add_argument("--base", default=None)
    args = ap.parse_args()

    cases_path = Path(args.cases).resolve()
    out_dir = Path(args.base).resolve() if args.base else cases_path.parent / "responses" / cases_path.stem

    cases = json.loads(cases_path.read_text(encoding="utf-8"))["cases"]

    results = []
    for case in cases:
        n = case["step"]
        step_data = load_step(out_dir, n)
        entry = {"step": n, "name": case["name"]}
        if step_data is None:
            entry.update({"passed": False, "missing": True, "checks": []})
        else:
            passed, checks = check_step(case, step_data, load_logs(out_dir, n))
            entry.update({"passed": passed, "missing": False, "checks": checks})
        results.append(entry)

    passed_n = sum(r["passed"] for r in results)
    total = len(results)

    out_dir.mkdir(parents=True, exist_ok=True)
    results_path = out_dir / "results.json"
    results_path.write_text(json.dumps({
        "model": args.model,
        "generated": f"{datetime.now():%Y-%m-%d %H:%M}",
        "summary": {"passed": passed_n, "total": total},
        "results": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[{args.model}] 코드 베이스 판정 결과")
    for r in results:
        tag = " (응답 없음)" if r.get("missing") else ""
        print(f"  step{r['step']} {r['name']}  {status_icon(r['passed'])}{tag}")
        for c in r["checks"]:
            if not c["ok"]:
                print(f"      ↳ {status_icon(False)} {c['name']}  {c['detail']}")
    print(f"결과: {passed_n}/{total} 통과")
    print(f"results: {results_path}")
    sys.exit(0 if passed_n == total else 1)


if __name__ == "__main__":
    main()
