# 실험: 동기 vs 스트리밍 체감 속도 비교

## 목적

`/api/v1/chat` (동기)와 `/api/v1/chat/stream` (SSE 스트리밍)을 동일한 메시지로 호출하여
단일 요청에서 TTFT(Time To First Token) 차이를 측정하고 기록한다.

> 동시 처리량 비교는 [k6 부하 테스트 계획](../../../.claude/tasks/load-test-k6.md) 참고.

---

## 비교 기준

| 항목 | 동기 | 스트리밍 |
|---|---|---|
| 첫 글자 도착 시간 (TTFT) | 전체 완료 후 | 생성 즉시 |
| 전체 응답 완료 시간 | 동일 | 동일 |
| 사용자 체감 | 멈췄다가 한 번에 출력 | 타이핑되듯 출력 |

---

## 실험 스텝

### Step 1. 동기 호출 — 응답 완료까지 시간 측정

```bash
time curl -s -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'
```

기록 항목:
- `real` 시간 (전체 소요 시간)
- 응답이 언제 터미널에 나타났는지 (멈췄다가 한 번에 출력)

---

### Step 2. 스트리밍 호출 — 첫 토큰 도착 시간 측정

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'
```

기록 항목:
- 첫 토큰이 터미널에 나타나기까지 걸린 시간 (체감)
- 전체 스트림 완료까지 걸린 시간

---

### Step 3. TTFT 정밀 측정 (선택)

스트리밍의 첫 토큰 도착 시간을 밀리초 단위로 측정한다.

```bash
curl -N -s -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}' \
  -w "\n\nTTFB: %{time_starttransfer}s / Total: %{time_total}s\n"
```

- `time_starttransfer`: 첫 바이트 수신까지 걸린 시간 (TTFT 근사값)
- `time_total`: 전체 완료까지 걸린 시간

---

### Step 4. 결과 기록

아래 표를 채운다.

| 측정 항목 | 동기 | 스트리밍 |
|---|---|---|
| 전체 완료 시간 | 2.182s | 2.362s |
| 첫 응답 체감 시간 (TTFT) | 2.182s (완료 후 출력) | 0.148s |
| 사용자 체감 대기 시간 단축 | — | 약 14배 (2.182s → 0.148s) |
| 사용자 체감 | 멈췄다가 한 번에 | 타이핑되듯 |

> - 전체 완료 시간은 거의 같다 — LLM이 생성하는 총 작업량이 동일하기 때문이다.
> 스트리밍의 이점은 완료 시간 단축이 아니라 **첫 글자가 보이는 시점**을 앞당기는 것이다.
> - 스트리밍 TTFT(첫 토큰이 도착하는 시간) ≈ Prefill 시간. 첫 토큰은 LLM이 입력을 처리하자마자 도착한다.


---

### [참고] Step 5. Prefill 시간 측정 (Ollama API 직접 호출)

스트리밍 TTFT에서 Prefill과 Generate 구간을 분리한다.
Ollama `/api/generate`는 응답에 `prompt_eval_duration`(Prefill)과 `eval_duration`(Generate)을 포함한다.

```bash
curl -s -X POST http://localhost:11434/api/generate \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5","prompt":"주문번호 2024-1234 배달 어디쯤에 있어요?","stream":false}' \
  | jq '{ prompt_eval_duration_ms: (.prompt_eval_duration / 1000000), eval_duration_ms: (.eval_duration / 1000000), eval_count: .eval_count }'
```

- `prompt_eval_duration`: Prefill 시간 (ns → ms 변환)
- `eval_duration`: Generate 시간 (ns → ms 변환)
- `eval_count`: 생성한 토큰 수

기록 항목:

| 측정 항목                                     | 값 |
|-------------------------------------------|---|
| Prefill 시간 (prompt_eval_duration) : 입력 처리 | 211ms |
| Generate 시간 (eval_duration) : 토큰 생성       | 2,892ms |
| 생성 토큰 수 (eval_count)                      | 132 tokens |
| 토큰당 생성 시간 (eval_duration / eval_count)    | 21.9ms/token |
