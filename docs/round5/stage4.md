# 4단계: Fallback 처리 + AI 코드 리뷰

## 구현 요약

- `AssistantController` / `SupportController` 전체 호출을 `try/catch`로 감싸고 예외 시 `fallback()` 반환
- 스택 트레이스는 `log.error`로 서버 내부에만, 응답엔 노출하지 않음
- Fallback 응답에 상담원 연결 번호(`1600-0987`) 포함

---

## Fallback 검증 — Tool 강제 실패

### 준비

`OrderTools.java`의 `getOrderDetail()`에 아래 한 줄 추가 후 기동

```java
if ("2024-1234".equals(orderId)) throw new RuntimeException("simulated Tool failure");
```



### 요청

```bash
curl.exe -s -w $'\nTIME_TOTAL=%{time_total}\n' -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: fallback-tool-fixed" -d "{\"message\":\"주문번호 2024-1234 상태 알려줘\"}"
```

**응답 (고객 노출)**

```
죄송해요, 일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주시거나 상담원(1600-0987)에게 문의해 주세요.
TIME_TOTAL=107.641962
```

**서버 로그 (내부)**

```
2026-06-14T17:10:01.601+09:00 INFO  [Assistant] sessionId=fallback-tool-fixed, message=주문번호 2024-1234 상태 알려줘
2026-06-14T17:11:49.143+09:00 DEBUG Executing tool call: getOrderDetail
2026-06-14T17:11:49.144+09:00 DEBUG Starting execution of tool: getOrderDetail
2026-06-14T17:11:49.144+09:00 INFO  [Tool] getOrderDetail(orderId=2024-1234)
2026-06-14T17:11:49.144+09:00 ERROR [Tool] 실행 실패 — tool=getOrderDetail
org.springframework.ai.tool.execution.ToolExecutionException: simulated Tool failure
  ... (스택 트레이스)
2026-06-14T17:11:49.148+09:00 ERROR [Assistant] 처리 중 오류 발생 — simulated Tool failure
org.springframework.ai.tool.execution.ToolExecutionException: simulated Tool failure
  ... (스택 트레이스)
```

### 관찰 — Tool 실패와 컨트롤러 Fallback의 관계

기본 Spring AI 동작에서는 Tool 예외를 `DefaultToolExecutionExceptionProcessor`가 가로채 LLM에 에러 신호로 전달한다.
그러면 컨트롤러 `catch`가 발동하지 않아 fallback 번호가 빠질 수 있다.
이번 구현에서는 `ToolFailureConfig`에서 `ToolExecutionExceptionProcessor`를 커스텀해 Tool 예외를 다시 던지도록 했다.
그 결과 Tool 실패가 `AssistantController`의 `catch`까지 전파되고, `fallback()` 응답에 `1600-0987`이 포함됐다.

**스택 트레이스 응답 노출**: 없음 ✓ (응답 본문에는 fallback 문구만 노출)

> 서버 로그에는 `[Tool] 실행 실패`와 `[Assistant] 처리 중 오류 발생`이 모두 ERROR로 남았다.
> 고객 응답에는 예외 클래스명, `simulated Tool failure`, 스택 트레이스가 노출되지 않고 상담원 연결 번호만 포함된다.
> 이 방식은 Tool 실패를 정상 상담 응답처럼 숨기지 않고 운영 로그/알림 대상으로 만들 수 있다는 장점도 있다.

---

## Fallback 검증 — LLM 연결 실패

### 준비

`application.yml`을 수정하거나 환경 변수로 `spring.ai.ollama.base-url`을 존재하지 않는 포트로 바꾼 뒤 재기동:

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:1  # 존재하지 않는 포트
```
### 요청

```bash
curl.exe -s -w $'\nTIME_TOTAL=%{time_total}\n' -X POST http://localhost:8080/api/v1/assistant -H "Content-Type: application/json" -H "X-Session-Id: fallback-llm-down" -d "{\"message\":\"비 오는 날 배달 늦으면 보상 받나요?\"}"
```

**응답 (고객 노출)**

```
죄송해요, 일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주시거나 상담원(1600-0987)에게 문의해 주세요.
TIME_TOTAL=0.223206
```

**서버 로그 (내부)**

```
2026-06-14T17:17:18.449+09:00 INFO  [Assistant] sessionId=fallback-llm-down, message=비 오는 날 배달 늦으면 보상 받나요?
2026-06-14T17:17:18.579+09:00 ERROR [Assistant] 처리 중 오류 발생 — I/O error on POST request for "http://localhost:1/api/embed": Connection refused: getsockopt: localhost/127.0.0.1:1
org.springframework.web.client.ResourceAccessException: I/O error on POST request for "http://localhost:1/api/embed": Connection refused: getsockopt: localhost/127.0.0.1:1
  at QuestionAnswerAdvisor.before(QuestionAnswerAdvisor.java:119)
  at AssistantController.ask(AssistantController.java:78)
  ... (스택 트레이스)
```

실패 지점: `QuestionAnswerAdvisor`가 RAG 임베딩 요청(`/api/embed`)을 Ollama로 보내는 시점에 연결 거부.

> 실패 지점이 `QuestionAnswerAdvisor.before()`인 것은 Advisor 실행 순서를 간접적으로 증명한다. 체인 순서: `InputGuardrail(5) → Memory(10) → RAG(20) → OutputGuardrail(50) → Performance(100)`. InputGuardrail과 Memory는 Ollama 호출 없이 정상 통과했고, RAG가 임베딩 요청(`/api/embed`)을 Ollama에 보내는 순간 연결 거부가 발생했다. 스택 트레이스에서 `QuestionAnswerAdvisor.before()` → `AssistantController.ask()` 순서로 나타난 것이 이를 확인해 준다. 이 요청은 0.223206초 안에 fallback으로 반환됐고, 응답 본문에는 연결 번호만 포함되며 스택 트레이스는 노출되지 않았다. LLM 연결 실패는 RAG 이후 채팅 생성 단계에서도 발생할 수 있지만, 어느 단계에서든 `Exception`이 컨트롤러 `catch`에 도달하면 동일한 `fallback()` 문구가 반환된다.

---

## 정량 비교 표

| 실패 지점 | 응답 본문 요약 | 스택 트레이스 노출 | 연결 번호 포함 | 서버 로그 수준 | 응답 시간 |
|---|---|---|---|---|---|
| Tool 실패 | "일시적인 오류, 상담원(1600-0987)" | ❌ | ✅ | ERROR | 107.641962초 |
| LLM 연결 실패 | "일시적인 오류, 상담원(1600-0987)" | ❌ | ✅ | ERROR | 0.223206초 |

> **Tool 실패와 LLM 실패의 차이**: 기본 Spring AI 설정에서는 Tool 예외가 LLM에 전달될 수 있지만, 이번 구현은 `ToolExecutionExceptionProcessor`를 커스텀해 예외를 컨트롤러 fallback으로 보낸다. LLM 연결 실패는 RAG 임베딩 또는 채팅 모델 호출 단계에서 바로 컨트롤러 `catch`까지 전파된다.

---

## AI 코드 리뷰

### 프롬프트

```
Spring AI 1.0으로 Prompt Injection 방어와 민감 정보 마스킹 Guardrail을 만들어줘.
```

### AI 생성 코드 원본

Codex가 생성한 코드: `AiGuardrailAdvisor` + `PromptInjectionDetector` + `SensitiveDataMasker` 등. (Configuration 등은 생략함)     

```java
public class AiGuardrailAdvisor implements BaseAdvisor {

    public static final String NAME = "ai-guardrail-advisor";

    private final PromptInjectionDetector promptInjectionDetector;
    private final SensitiveDataMasker sensitiveDataMasker;
    private final int order;

    public AiGuardrailAdvisor(PromptInjectionDetector promptInjectionDetector, SensitiveDataMasker sensitiveDataMasker) {
        this(promptInjectionDetector, sensitiveDataMasker, 0);
    }

    public AiGuardrailAdvisor(PromptInjectionDetector promptInjectionDetector, SensitiveDataMasker sensitiveDataMasker,
            int order) {
        this.promptInjectionDetector = promptInjectionDetector;
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        Prompt prompt = request.prompt();
        assertSafePrompt(prompt);

        List<Message> maskedMessages = prompt.getInstructions().stream()
                .map(this::maskMessage)
                .toList();

        Prompt maskedPrompt = Prompt.builder()
                .messages(maskedMessages)
                .chatOptions(prompt.getOptions())
                .build();

        return request.mutate()
                .prompt(maskedPrompt)
                .context(withFlag(request.context(), "guardrail.inputMasked", !maskedMessages.equals(prompt.getInstructions())))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResults() == null) {
            return response;
        }

        List<Generation> maskedGenerations = chatResponse.getResults().stream()
                .map(this::maskGeneration)
                .toList();

        ChatResponse maskedChatResponse = ChatResponse.builder()
                .from(chatResponse)
                .generations(maskedGenerations)
                .build();

        return response.mutate()
                .chatResponse(maskedChatResponse)
                .context(withFlag(response.context(), "guardrail.outputMasked", !maskedGenerations.equals(chatResponse.getResults())))
                .build();
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return NAME;
    }

    private void assertSafePrompt(Prompt prompt) {
        String content = prompt.getContents();
        if (promptInjectionDetector.isInjectionAttempt(content)) {
            throw new PromptInjectionBlockedException("Prompt injection attempt was blocked.");
        }
    }

    private Message maskMessage(Message message) {
        String maskedText = sensitiveDataMasker.mask(message.getText());
        if (maskedText.equals(message.getText())) {
            return message;
        }

        if (message instanceof UserMessage userMessage) {
            return UserMessage.builder()
                    .text(maskedText)
                    .media(userMessage.getMedia())
                    .metadata(userMessage.getMetadata())
                    .build();
        }
        if (message instanceof SystemMessage systemMessage) {
            return SystemMessage.builder()
                    .text(maskedText)
                    .metadata(systemMessage.getMetadata())
                    .build();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return new AssistantMessage(maskedText, assistantMessage.getMetadata(), assistantMessage.getToolCalls(),
                    assistantMessage.getMedia());
        }
        return message;
    }

    private Generation maskGeneration(Generation generation) {
        AssistantMessage output = generation.getOutput();
        String maskedText = sensitiveDataMasker.mask(output.getText());
        if (maskedText.equals(output.getText())) {
            return generation;
        }

        AssistantMessage maskedOutput = new AssistantMessage(maskedText, output.getMetadata(), output.getToolCalls(),
                output.getMedia());
        return new Generation(maskedOutput, generation.getMetadata());
    }

    private Map<String, Object> withFlag(Map<String, Object> context, String key, boolean value) {
        Map<String, Object> updated = new LinkedHashMap<>();
        if (context != null) {
            updated.putAll(context);
        }
        updated.put(key, value);
        return updated;
    }
}

```

```java
public class PromptInjectionDetector {

    private final List<Pattern> denyPatterns;

    public PromptInjectionDetector() {
        this(List.of(
                Pattern.compile("\\b(ignore|disregard|forget)\\b.{0,80}\\b(previous|prior|above|earlier)\\b.{0,80}\\b(instruction|prompt|rule)s?\\b"),
                Pattern.compile("\\b(reveal|show|print|dump|exfiltrate)\\b.{0,80}\\b(system|developer|hidden|internal)\\b.{0,80}\\b(prompt|message|instruction|rule)s?\\b"),
                Pattern.compile("\\b(system|developer)\\s+(prompt|message)\\b"),
                Pattern.compile("\\b(jailbreak|prompt\\s*injection|do\\s*anything\\s*now|dan\\s*mode)\\b"),
                Pattern.compile("\\b(override|bypass)\\b.{0,80}\\b(safety|guardrail|policy|instruction|rule)s?\\b"),
                Pattern.compile("(이전|앞선|위의).{0,30}(지시|명령|규칙).{0,30}(무시|잊어|따르지)"),
                Pattern.compile("(시스템|개발자|숨겨진|내부).{0,30}(프롬프트|메시지|지시|규칙).{0,30}(공개|출력|보여|알려)"),
                Pattern.compile("(탈옥|프롬프트\\s*인젝션|규칙\\s*우회|안전장치\\s*우회)")));
    }

    public PromptInjectionDetector(List<Pattern> denyPatterns) {
        this.denyPatterns = List.copyOf(denyPatterns);
    }

    public boolean isInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return denyPatterns.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}

```

```java
public class SensitiveDataMasker {

    private final List<MaskingRule> rules = List.of(
            new MaskingRule(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"), "[EMAIL]"),
            new MaskingRule(Pattern.compile("\\b(?:\\+?\\d{1,3}[-.\\s]?)?(?:\\(?\\d{2,4}\\)?[-.\\s]?)?\\d{3,4}[-.\\s]?\\d{4}\\b"), "[PHONE]"),
            new MaskingRule(Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b"), "[CARD]"),
            new MaskingRule(Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), "[SSN]"),
            new MaskingRule(Pattern.compile("\\b\\d{6}-[1-4]\\d{6}\\b"), "[RRN]"),
            new MaskingRule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "[AWS_ACCESS_KEY]"),
            new MaskingRule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"), "[OPENAI_API_KEY]"),
            new MaskingRule(Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/=-]{20,}\\b", Pattern.CASE_INSENSITIVE), "Bearer [TOKEN]"),
            new MaskingRule(Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"), "[JWT]"),
            new MaskingRule(Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|token)\\b\\s*[:=]\\s*[^\\s,;]+"), "$1=[SECRET]"));

    public String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String masked = text;
        for (MaskingRule rule : rules) {
            masked = rule.pattern().matcher(masked).replaceAll(rule.replacement());
        }
        return masked;
    }

    private record MaskingRule(Pattern pattern, String replacement) {
    }
}

```

### 결함 분석

#### 결함 1 — SensitiveDataMasker 전화번호 패턴이 주문번호·상담번호를 오탐

**문제 코드**

```java
new MaskingRule(Pattern.compile(
    "\\b(?:\\+?\\d{1,3}[-.\\s]?)?(?:\\(?\\d{2,4}\\)?[-.\\s]?)?\\d{3,4}[-.\\s]?\\d{4}\\b"),
    "[PHONE]")
```

**문제점**

`\d{3,4}[-.\s]?\d{4}` 구조가 너무 넓어 `2024-1234`(주문번호), `1600-0987`(시스템 프롬프트의 상담원 번호)도 `[PHONE]`으로 치환된다.

- Tool이 반환한 주문번호 `2024-1234` → `[PHONE]` → LLM이 "주문을 찾을 수 없습니다" 오답
- 시스템 프롬프트의 `1600-0987` → `[PHONE]` → Fallback 안내 문구 파괴
- `[PHONE]`으로 완전 치환하면 맥락이 사라져 고객이 어떤 번호인지 알 수 없다 (과잉 마스킹)

이번 실험에서 `2024-1234`가 PHONE_KR(`01[016789]...`)에 매칭되지 않아 오탐 없음을 수치로 확인했다.

**개선 방안 (이번 라운드에서 배운 방식)**

```java
// 한국 휴대전화만 정밀 매칭 — 선두 01[016789] 고정
Pattern.compile("01[016789][\\s-]?\\d{3,4}[\\s-]?\\d{4}")
// 치환: 010-****-5678 (뒤 4자리 유지, 맥락 보존)
```

---

#### 결함 2 — `before()`에서 SystemMessage까지 마스킹해 시스템 프롬프트 파괴

**문제 코드**

```java
// before() 안
List<Message> maskedMessages = prompt.getInstructions().stream()
        .map(this::maskMessage)   // SystemMessage도 포함됨
        .toList();

// maskMessage() 안
if (message instanceof SystemMessage systemMessage) {
    return SystemMessage.builder()
            .text(maskedText)     // 시스템 프롬프트를 마스킹
            ...
```

**문제점**

`prompt.getInstructions()`는 UserMessage뿐 아니라 **SystemMessage와 대화 이력(AssistantMessage)** 을 모두 포함한다.          
결함 1의 넓은 전화번호 패턴과 결합하면

- 시스템 프롬프트 안의 `1600-0987` → `[PHONE]` → Fallback 안내 파괴
- RAG가 주입한 정책 문서의 수치(배상 금액, 날짜) → `[PHONE]`/`[SSN]` 오탐 → LLM이 잘못된 정책 안내

마스킹은 LLM **출력(after)** 에만 적용해야 한다. 입력 마스킹이 필요하다면 UserMessage에 한정해야 한다.

**개선 방안**

- `OutputGuardrailAdvisor`(`after`)에서만 마스킹 적용
- `before()`에서는 UserMessage만 Injection 체크 대상으로 삼고, SystemMessage는 건드리지 않는다
- 이번 구현에서 `containsSensitive(content)` → `masker.mask(content)` 흐름이 응답(`after`) 단에서만 동작해 시스템 프롬프트를 보호했다

---

#### 결함 3 — 예외를 던지는 Injection 차단 → Short-circuit 아님, 500 에러 처리 위험

**문제 코드**

```java
private void assertSafePrompt(Prompt prompt) {
    String content = prompt.getContents();
    if (promptInjectionDetector.isInjectionAttempt(content)) {
        throw new PromptInjectionBlockedException("Prompt injection attempt was blocked.");
    }
}
```

**문제점**

예외를 던지면 `chain.nextCall()`은 실행되지 않지만 예외가 Advisor 체인 밖으로 전파된다.
컨트롤러에서 `catch`하지 않으면 Spring이 500 에러 응답을 만든다. 기본 Spring Boot 에러 응답은 스택 트레이스를 클라이언트에 노출하지 않지만, `server.error.include-stacktrace` 같은 에러 속성 설정에 따라 노출될 수 있으므로 예외 기반 차단을 컨트롤러 밖으로 흘려보내지 않는 편이 안전하다.
`catch`하더라도 예외 객체 생성·전파 비용이 발생하며, 공격자가 초당 수백 건을 보내면 예외 처리 비용이 누적된다.

진짜 short-circuit은 "예외 없이 즉시 안전 응답을 반환"하는 것이다. 이번 실험에서 차단된 1~4번 시나리오에서 PerformanceAdvisor 로그조차 찍히지 않음을 확인했다.

**개선 방안**

```java
GuardrailResult result = check(extractUserText(request));
if (!result.allowed()) {
    log.warn("[InputGuardrail] 차단 — reason={}", result.reason());
    return shortCircuit(request, result.fallbackMessage()); // 예외 없이 즉시 반환
}
return chain.nextCall(request);  // 통과한 경우만 LLM 호출
```

고객 친화적 메시지를 즉시 반환하고, 스택 트레이스는 서버 `log.warn`에만 남긴다.

---

### 결함 요약

| # | 결함 | 심각도 | 이번 라운드에서 배운 해결책 |
|---|---|---|---|
| 1 | 전화번호 패턴 과잉 매칭 → 주문번호·시스템 프롬프트 오탐 | 높음 | `01[016789]` 선두 고정, 뒤 4자리 유지 치환 |
| 2 | `before()`에서 SystemMessage 마스킹 → 시스템 프롬프트 파괴 | 높음 | 마스킹은 `after()`(출력 단)에서만, UserMessage에 한정 |
| 3 | 예외 방식 차단 → short-circuit 아님, 500 에러 처리 위험 | 높음 | `shortCircuit()` 헬퍼로 예외 없이 즉시 응답 반환 |
