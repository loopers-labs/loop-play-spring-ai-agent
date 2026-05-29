# Tool 왕복 로그 관찰 기록

- 실험 일시: 2026-05-22
- 서버 로그: `docs/week2/stage1/responses/roundtrip/server_logs_obs.txt`
- 모델: qwen2.5 (Ollama)
- 목표: `PerformanceLoggingAdvisor`로 Tool 왕복(LLM → Tool → LLM)을 로그로 직접 관찰하고, Tool 등록이 입력 토큰에 주는 비용을 수치화한다.

## 목차

1. [왕복 타임라인 — 4종 로그](#1-왕복-타임라인--4종-로그)
2. [입력 토큰 비교](#2-입력-토큰-비교)
3. [분석](#3-분석)

---

## 1. 왕복 타임라인 — 4종 로그

### 1-1. Tool 왕복 로그 타임라인
질문 `"주문번호 2024-1234 배달 어디쯤에 있어요?"` 한 번이 **LLM 2회 + Tool 1회**로 처리된다.

```
23:44:07  request    ChatClientRequest — Tool 3개 등록(OllamaOptions)                  ← ① 1차 입력 구성
23:44:16  [LLM #1]   elapsed=9073ms   input=2086  output=29                            ← ① tool_call 결정
23:44:16  [Tool]     getDeliveryStatus(orderId=2024-1234)                              ← ② Java 메서드 실행
23:44:17  [LLM #2]   elapsed=1232ms   input=4297  output=63                            ← ③ 자연어 응답
23:44:17  [PERF]     elapsed=10337ms  총호출=2회 누적입력=6383 누적출력=92 누적합계=6475   ← ④ 최종 완료
```

| 단계 | 로그 | input | output | elapsed |
|---|---|---|---|---|
| ① 1차 LLM 호출 | `[LLM #1]` | 2,086 | 29 (tool_call JSON) | 9,073ms |
| ② Tool 실행 | `[Tool] getDeliveryStatus` | — | — | — |
| ③ 2차 LLM 호출 | `[LLM #2]` | 4,297 | 63 (자연어) | 1,232ms |
| **④ 최종 (누적)** | `[PERF]` | **6,383** | **92** | **10,337ms** |

### 1-2. (참고) 각 단계의 실제 로그와 관찰 포인트

#### ① 1차 LLM 호출 — Tool 정의(JSON 스키마) 포함

`SimpleLoggerAdvisor`가 어드바이저 체인 진입 시점의 `ChatClientRequest`를 기록한다. `OllamaOptions`에 Tool 3개가 등록된 채 Ollama로 전달된다.

```
23:44:07 DEBUG SimpleLoggerAdvisor - request: ChatClientRequest[
  prompt=Prompt{
    messages=[
      SystemMessage{textContent='# 역할 ...'},
      UserMessage{content='주문번호 2024-1234 배달 어디쯤에 있어요?'}
    ],
    modelOptions=org.springframework.ai.ollama.api.OllamaOptions@49231b4e  ← Tool 3개 포함
  },
  context={}
]
```

> Spring AI 1.0의 `OllamaChatModel` / `OllamaApi`는 TRACE 로그를 안 찍어 실제 전송되는 `tools` 배열 JSON 원문은 보이지 않는다. Tool 포함 증거는 `OllamaOptions` 객체 참조와 1차 입력 토큰 2,086으로 간접 확인한다. `output=29`는 tool_call JSON이다.

#### ② `[Tool]` 실행 — Java 메서드 실제 호출

```
23:44:16 DEBUG DefaultToolCallingManager  - Executing tool call: getDeliveryStatus
23:44:16 DEBUG MethodToolCallback         - Starting execution of tool: getDeliveryStatus
23:44:16 INFO  OrderTools                 - [Tool] getDeliveryStatus(orderId=2024-1234)
23:44:16 DEBUG MethodToolCallback         - Successful execution of tool: getDeliveryStatus
23:44:16 DEBUG DefaultToolCallResultConverter - Converting tool result to JSON.
```

> `[Tool]` 로그는 `[LLM #1]`과 `[LLM #2]` 사이에 찍힌다. `DefaultToolCallResultConverter`가 실행 결과를 JSON으로 변환한 뒤 2차 호출이 시작된다.

#### ③ 2차 LLM 호출 — `ToolResponseMessage` 추가

2차 호출의 프롬프트 전문은 `SimpleLoggerAdvisor`가 체인 바깥에서만 동작해 로그에 안 나온다. 대신 입력 토큰 증가(2,086 → 4,297, **+2,211**)로 `ToolResponseMessage`가 누적됐음을 확인한다. 누적분 분해는 [3절](#3-분석)에서 다룬다.

---

## 2. 입력 토큰 비교

같은 질문 `"안녕하세요"`로 **Tool 등록 유무**만 바꿔 비교한다.

| 엔드포인트 | 입력 토큰 | 출력 토큰 | 응답 시간 |
|---|---|---|---|
| `/api/v1/chat` (Tool 없음) | 32 | 12 | 443ms |
| `/api/v1/assistant` (Tool 3개 등록, 미호출) | 2,068 | 64 | 7,226ms |

> 입력 토큰은 Ollama `promptEvalCount` 기준이라 KV 캐시 적중 여부에 좌우된다. `/api/v1/chat`의 32는 시스템 프롬프트를 캐시한 뒤 사용자 메시지만 계산한 값이다.

---

## 3. 분석

### 두 엔드포인트의 차이(32 → 2,068, 약 65배)는 어디서 오는가?

**Tool 정의**다. `/api/v1/assistant`는 `defaultTools(orderTools)`로 Tool 3개가 등록돼 있고, Spring AI가 `@Tool` / `@ToolParam`을 OpenAI 호환 JSON 스키마로 변환해 **매 요청** `tools` 배열에 싣는다. 호출하지 않아도 등록만으로 정의 전체가 프롬프트에 붙는다.

```
/api/v1/chat:        [System Prompt] + [User 메시지]
/api/v1/assistant:   [System Prompt] + [User 메시지] + [Tool 정의 3개 JSON 스키마]
                                                        ↑ 이 차이가 ~2,036 토큰
```

### Tool이 실제 호출되면 Round 1 대비 몇 배인가?

| 기준 | 입력 토큰 | Round 1(32) 대비 |
|---|---|---|
| 2차 LLM 단일 호출 | 4,297 | **≈ 134배** |
| 왕복 누적 | 6,383 | **≈ 200배** |


#### 무엇이 2배를 만드나 — 2차 호출(4,297)에 누적되는 4가지

각 단계의 입력에 무엇이 들어가는지 비교하면, **2차에서만 ③·④가 새로 붙는다.**

| 누적 항목 | 등록만(2,068) | 1차 호출(2,086) | 2차 호출(4,297) | 2배의 원인? |
|---|---|---|---|---|
| ① System Prompt + User 메시지 | ✓ | ✓ | ✓ | ✗ (상관없음) |
| ② Tool 정의 JSON 스키마 3개 (~2,036) | ✓ | ✓ | ✓ | ✗ (상관없음) |
| ③ 1차 LLM 응답 (tool_call JSON, ~29) | — | — | ✓ | △ (미미) |
| ④ **`ToolResponseMessage`** (Tool 결과 JSON, `DeliveryStatusView` 직렬화) | — | — | ✓ | ✓ (이게 원인) |

①·②는 등록만·1차·2차에 모두 붙으므로 차이를 못 만든다. ③은 미미하다. 결국 2,068 → 4,297로 **2배 이상** 늘어난 원인은 **④ Tool 결과가 대화에 통째로 추가**되기 때문이다. (Tool 실제 호출은 1차·2차 LLM 비용을 모두 지불한다.)

> [참고] `promptEvalCount`는 KV 캐시 적중 여부에 좌우되므로(2절 각주), +2,211에는 ④의 순수 크기 외에 2차 호출에서 prefix가 다시 카운트된 부분이 섞여 있을 수 있다. 로그만으로 ④ 단독 
> 토큰 수를 
> 정확히 분리하긴 어렵다.