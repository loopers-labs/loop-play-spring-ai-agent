# LLM 프롬프트 전문 및 성능 측정 관찰 기록

`/api/v1/support` 호출 시 실제로 LLM에 전달되는 내용과 `PerformanceLoggingAdvisor`가 기록한 성능 지표를 정리한다.

---

## 테스트 요청

```bash
curl -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message":"주문한 음식이 아직 안 왔어요."}'
```

---

## 실제 LLM에 전달된 프롬프트

Spring AI `ChatClient`는 Ollama에 두 개의 메시지를 전달한다.

### 1. System 메시지

`BaedalPrompt.SYSTEM_PROMPT` — `src/main/resources/prompts/delivery_agent_system_prompt.md` 파일의 전문이 그대로 전달된다.


### 2. User 메시지

사용자 입력 + `BeanOutputConverter.getFormat()` 가 개행(`\n`)으로 이어붙여진다.

> `ChatModelCallAdvisor`의 `augmentWithFormatInstructions()` 가 user message에 format 지시를 append한다.  
> 코드: `userMessage.getText() + System.lineSeparator() + outputFormat`

#### 2-1. 사용자 입력 (예시)

```
주문한 음식이 아직 안 왔어요.
```

#### 2-2. BeanOutputConverter가 자동 추가하는 JSON 형식 지시

`chatClient.prompt().user(...).call().entity(SupportResponse.class)` 호출 시 Spring AI가 자동으로 아래 텍스트를 user message에 추가한다.

```
Your response should be in JSON format.
Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
Do not include markdown code blocks in your response.
Remove the ```json markdown from the output.
Here is the JSON Schema instance your output must adhere to:
```{JSON Schema}```
```

#### 2-3. SupportResponse JSON Schema (자동 생성)

`BeanOutputConverter`가 `victools/jsonschema-generator`로 `SupportResponse.class`에서 생성한 스키마:

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "category" : {
      "type" : "string",
      "enum" : [ "ORDER", "DELIVERY", "REFUND", "PAYMENT", "FOOD_QUALITY", "ETC" ]
    },
    "escalationRequired" : {
      "type" : "boolean"
    },
    "neededInfo" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "nextAction" : {
      "type" : "string"
    },
    "summary" : {
      "type" : "string"
    },
    "urgency" : {
      "type" : "string",
      "enum" : [ "LOW", "NORMAL", "HIGH", "CRITICAL" ]
    }
  },
  "additionalProperties" : false
}
```

---

## 성능 측정 결과 (PerformanceLoggingAdvisor 출력)

```
12:27:24 INFO  c.b.s.PerformanceLoggingAdvisor - [PERF] elapsed=16232ms input=4558 output=83 total=4641
12:27:24 WARN  c.b.s.PerformanceLoggingAdvisor - [PERF] 응답 시간 임계값 초과 — elapsed=16232ms
```

| 항목 | 값 | 설명 |
|------|----|------|
| 응답 시간 | 16,232ms (약 16초) | SLOW_RESPONSE_THRESHOLD_MS=3000 초과 → WARN 발생 |
| 입력 토큰 | 4,558 | System Prompt + User 입력 + JSON 형식 지시 포함 |
| 출력 토큰 | 83 | JSON 응답 1개 |
| 총 토큰 | 4,641 | 입력 + 출력 합계 |

### 토큰 분포 해석

- **입력 4,558 토큰**: 대부분 System Prompt가 차지한다. `delivery_agent_system_prompt.md`는 11개 예시와 상세 규칙을 포함하여 약 4,400 토큰 이상으로 추정된다. JSON 형식 지시(BeanOutputConverter)가 약 100~150 토큰을 추가한다.
- **출력 83 토큰**: 아래 JSON 응답 기준

```json
{
  "summary": "기다리시느라 답답하셨겠습니다. 주문 상태를 확인해드리겠습니다. 주문번호를 알려주시겠어요?",
  "category": "DELIVERY",
  "urgency": "NORMAL",
  "nextAction": "주문 상태 조회 요청",
  "neededInfo": ["주문번호"],
  "escalationRequired": false
}
```

### 응답 시간 이슈

- 로컬 Ollama + `qwen2.5` 모델 사용 (cold start 없음, 이미 로드됨)
- 16초는 모델 추론 시간 자체가 느린 것 (`temperature=0.3`, 입력 4,558 토큰)
- 임계값 3,000ms를 초과하여 WARN이 기록됨 → 실제 서비스라면 모델 교체 또는 프롬프트 축소 검토 대상

---

## System Prompt의 응답 형식 섹션은 중복인가

`BeanOutputConverter`가 JSON 형식 지시를 자동으로 추가한다면, `delivery_agent_system_prompt.md`의 `# 응답 형식` 섹션은 불필요한 것 아닌가 하는 의문이 생긴다.

결론: **부분적으로 중복이다 — 그러나 전부 제거할 수는 없다.**

`BeanOutputConverter`가 LLM에게 알려주는 건 **구조(shape)** 뿐이다.

```json
"urgency": { "type": "string", "enum": ["LOW", "NORMAL", "HIGH", "CRITICAL"] }
```

이 스키마만 보면 LLM은 "4가지 중 하나를 고르면 된다"는 것만 안다. 언제 CRITICAL을 쓰는지, HIGH와 NORMAL의 기준이 무엇인지는 모른다.

| System Prompt 내용 | BeanOutputConverter가 커버하는가 | 결론 |
|--------------------|----------------------------------|------|
| "JSON으로만 응답하라" | 커버함 (RFC8259 준수, 마크다운 금지 포함) | 중복 |
| `## JSON 스키마` 블록 | 커버함 (자동 생성 스키마로 대체됨) | 중복 |
| `urgency` 분류 가이드 (CRITICAL/HIGH/NORMAL/LOW 기준) | 커버 안 함 — 값 목록만 알려줄 뿐 | 필요 |
| `neededInfo` 표준 항목 리스트 | 커버 안 함 | 필요 |
| `nextAction` 4가지 값의 사용 조건 | 커버 안 함 | 필요 |
| `escalationRequired` 사용 조건 | 커버 안 함 | 필요 |

토큰을 줄이고 싶다면 System Prompt에서 `## JSON 스키마` 블록과 "JSON으로만 응답하라" 지시는 제거 가능하다. 나머지 필드별 의미 규칙은 스키마만으로 대체할 수 없어 반드시 남겨야 한다.

---

## 참고: 프롬프트 전문이 DEBUG 로그에 나타나지 않는 이유

`logback-spring.xml`에서 `org.springframework.ai` 로거를 `DEBUG`로 설정해도 프롬프트 전문은 로그에 출력되지 않는다.

Spring AI는 프롬프트 전문(실제 메시지 내용)을 `TRACE` 레벨 또는 `SENSITIVE_DATA_MARKER`가 붙은 로거로만 출력한다.  
`DEBUG` 레벨에서는 메타데이터·흐름 정보만 로깅되며, 개인정보 포함 가능성이 있는 메시지 본문은 의도적으로 숨긴다.

프롬프트 내용 확인이 필요한 경우: `spring.ai.chat.observations.include-prompt=true` 설정 추가 후 Micrometer Observation 활성화 필요.
