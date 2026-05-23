# Observability — 토큰·응답 시간 측정 + 운영 비용 분석

> Round 1 / 4단계 — `PerformanceLoggingAdvisor` 로 LLM 호출의 응답 시간·토큰 사용량을 정량 측정. System Prompt 길이가 운영 비용에 미치는 영향, 측정 과정에서 발견한 advisor 누적 bug + 수정.

## 구현

### `PerformanceLoggingAdvisor`

```java
@Slf4j
@Component
public class PerformanceLoggingAdvisor implements CallAdvisor {

    @Override public String getName() { return "PerformanceLoggingAdvisor"; }
    @Override public int getOrder()   { return 100; }   // 체인 바깥쪽에서 왕복 시간 측정

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse response = chain.nextCall(request);
        long elapsedMs = System.currentTimeMillis() - start;

        Usage usage = extractUsage(response);   // null 방어
        if (usage != null) {
            log.info("[LLM] elapsedMs={} inputTokens={} outputTokens={} totalTokens={}",
                    elapsedMs,
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        } else {
            log.info("[LLM] elapsedMs={} (usage metadata unavailable)", elapsedMs);
        }
        return response;
    }
}
```

`SupportService` 의 두 `ChatClient` 인스턴스 모두에 `.defaultAdvisors(performanceAdvisor)` 등록 — Structured Output 호출과 자유 텍스트 동기 호출(스트리밍은 `CallAdvisor` 적용 안 됨) 모두 측정 가능.

## 실험 1 — 6 케이스 토큰 측정

각 시나리오 1회 호출, advisor 가 로그에 출력.

| # | 시나리오 | elapsedMs | **inputTokens** | outputTokens | totalTokens |
|---|---|---:|---:|---:|---:|
| S1 | 배달 위치 | 5,702 | 2,655 | 147 | 2,802 |
| S2 | 취소·환불 | 5,629 | 2,656 | 145 | 2,801 |
| S3 | 라이더 사고 | 6,516 | 2,654 | 180 | 2,834 |
| ATK1 | 사장님 전화번호 | 5,442 | 2,642 | 138 | 2,780 |
| ATK2 | 환불 협박 | 6,203 | 2,651 | 169 | 2,820 |
| ATK3 | 쿠팡이츠 비교 | 5,655 | 2,651 | 146 | 2,797 |
| **평균** | | **5,858** | **2,651** | **154** | **2,806** |

### ⚡ 핵심 발견 — 운영 비용의 95%가 system prompt

| 측정 | 값 | 의미 |
|---|---|---|
| `inputTokens` 변동 | 2,642~2,656 (14 토큰) | **거의 일정** — 사용자 메시지 길이 영향 미미 |
| `outputTokens` 변동 | 138~180 | 응답 길이가 시나리오마다 약간 다름 |
| `inputTokens / totalTokens` | **94.5%** | 운영 비용의 95%가 input = 사실상 system prompt |
| `elapsedMs` 변동 (warm) | 5,442~6,516 | cold start 15초에서 안정화 |

→ **system prompt 1KB 늘어나면 모든 호출이 그만큼 더 비싸진다.** 응답 자체는 130~180 토큰으로 적음.
→ 1단계 보강의 *"`CORE_GUARDRAILS` 공유"* 패턴이 매우 합리적 — 두 번 정의하지 않고 한 번만 토큰 비용 지불.

## 실험 2 — System Prompt 2배 실험 (quest 명세 요구)

`SYSTEM_PROMPT` 를 두 번 이어붙여 글자 수 2배. 같은 시나리오 1로 측정.

| Prompt 변형 | 글자 수 | inputTokens | outputTokens | totalTokens | elapsedMs |
|---|---:|---:|---:|---:|---:|
| 1배 (원본 `SYSTEM_PROMPT`) | 7,144 | **2,655** | 149 | 2,804 | 11,764 |
| 2배 (동일 prompt 이어붙임) | 14,290 | **4,096** | 133 | 4,229 | 15,027 |
| **증가율** | +100% | **+54%** | -11% | +51% | +28% |

### 흥미로운 발견 — 글자 2배 ≠ 토큰 2배

`7,144 → 14,290` (글자 +100%) 인데 토큰은 `2,655 → 4,096` (+54%).

| 측정 | 1배 | 2배 |
|---|---:|---:|
| 토큰/글자 비율 | 0.371 | **0.286** ← 효율적 |

**가능한 원인**:
- BPE tokenizer 가 같은 패턴(동일 한국어 단어·문장 반복)을 *효율적으로* 처리
- 토큰화 사전에 자주 등장하는 토큰이 큰 단위(여러 글자 = 1 토큰)로 압축됨
- 또는 Ollama 의 prompt caching·KV cache 가 동일 텍스트 재인식

→ **운영 비용 측면에서 *"prompt 길이 2배 ≠ 비용 2배"***. 다만 +54% 도 여전히 큰 비용. **운영 비용 최적화의 핵심은 system prompt 길이 관리**.

→ `outputTokens` 가 오히려 감소(-11%) — input 이 길어진 게 output 길이를 늘리지 않음. LLM 자연 변동.

→ `elapsedMs` +28% — input 처리 시간 증가하지만 글자 비례는 아님.

## 실험 3 — Advisor 누적 bug 발견 + 수정

### 문제 발견

System Prompt 2배 실험 진행 중 advisor 로그가 **같은 호출에 2회 출력**되는 현상 관찰:

```text
[LLM] elapsedMs=15027 inputTokens=4096 outputTokens=133 totalTokens=4229
[LLM] elapsedMs=15027 inputTokens=4096 outputTokens=133 totalTokens=4229   ← 같은 thread, 같은 timestamp, 같은 값
```

같은 thread (`nio-8080-exec-5`), 같은 timestamp, 같은 값 → **PromptLabController 의 builder 가 매 요청마다 advisor 를 누적 등록하여 chain 에서 2회 실행된 결과**.

### 원인 — `ChatClient.Builder` 의 누적

```java
// 문제 코드 (수정 전)
@RequiredArgsConstructor
public class PromptLabController {
    private final ChatClient.Builder builder;   // Spring 이 같은 인스턴스 주입
    private final PerformanceLoggingAdvisor performanceAdvisor;

    @PostMapping
    public PromptLabResult experiment(...) {
        ChatClient client = builder.defaultSystem(...)
                .defaultAdvisors(performanceAdvisor)   // 호출마다 advisor 누적!
                .build();
        ...
    }
}
```

`ChatClient.Builder` 가 singleton 또는 동일 인스턴스로 주입되면 매 요청마다 `defaultAdvisors()` 호출이 advisor list 에 *추가* — 2번째 호출은 advisor 2개, 3번째는 3개 ... 식으로 누적.

이건 1단계 리뷰에서 loopers-len 보완점 B *"매 요청마다 build 누적은 2주차 Tool Calling 버그 자리"* 가 가리킨 정확한 자리.

### 수정

```java
// 수정 후
@RequiredArgsConstructor
public class PromptLabController {
    private final ChatModel chatModel;   // ChatModel 직접 주입
    private final PerformanceLoggingAdvisor performanceAdvisor;

    @PostMapping
    public PromptLabResult experiment(...) {
        ChatClient client = ChatClient.builder(chatModel)   // 매 요청마다 fresh
                .defaultSystem(...)
                .defaultAdvisors(performanceAdvisor)
                .build();
        ...
    }
}
```

`ChatClient.builder(chatModel)` 정적 팩토리로 **매 요청마다 fresh builder** 생성. 누적 회피.

### 검증

수정 후 같은 1배·2배 호출:

| 호출 | thread | advisor 로그 출력 |
|---|---|---|
| 1배 | exec-1 | **1줄** (inputTokens=2655) |
| 2배 | exec-2 | **1줄** (inputTokens=4096) |

→ 각 호출당 advisor 1회 호출. **누적 bug 해소**.

### 4단계 Observability 의 진짜 가치

> **advisor 없이는 이 누적 bug 를 발견할 수 없었다.**
> Observability 가 단순히 *비용 측정* 도구가 아니라 *코드 결함 발견* 도구라는 사실 직접 검증.

같은 패턴이 2주차 Tool Calling 에서 더 큰 문제로 터질 수 있음 — Tool 등록도 builder 에 누적됨. `ChatClient.builder(chatModel)` 패턴은 그 자리도 사전에 회피.

## 결론

### 핵심 발견 4가지

1. **운영 비용의 95%가 system prompt** — `inputTokens / totalTokens = 94.5%`. system prompt 길이 관리가 비용 최적화의 핵심.
2. **글자 2배 ≠ 토큰 2배 (+54%)** — BPE tokenizer 의 패턴 압축 효과. 다만 +54% 도 큰 비용.
3. **`SupportService` 의 캐싱 패턴이 합리적** — 한 번만 build, advisor·prompt 모두 한 번만 비용 지불.
4. **`PromptLabController` 의 누적 bug** — `ChatClient.builder(chatModel)` 정적 팩토리 패턴으로 수정. Observability 가 단순 측정이 아니라 *결함 발견* 도구.

### 운영 시사점

| 자리 | 관찰 | 권장 |
|---|---|---|
| `BaedalPrompt.SYSTEM_PROMPT` (7섹션, 약 7,144자) | inputTokens 약 2,655 | **유지** — 가드레일·분류 가이드는 운영 필수 |
| Prompt 영역 변경 시 | 모든 호출이 영향 받음 | 변경 전후 토큰 측정 필수 (regression 추적) |
| 일관성 메트릭 + 비용 메트릭 결합 | 본 1단계는 `categoryConsistency` 등 *품질* 측정에 집중 | 운영에서는 `inputTokens × 호출 수 × 단가` 의 *비용* 도 추적 |
| Spring AI `Builder` 패턴 사용 | 매 요청마다 `build()` 호출은 누적 위험 | `ChatClient.builder(chatModel)` 정적 팩토리 또는 1회 빌드 후 캐싱 |

### 후속 과제

| 항목 | 내용 |
|---|---|
| 토큰 비용 추적 dashboard | 운영 환경에서 daily 토큰 누적 + 시나리오별 분포 시각화 |
| Streaming 호출에도 측정 | `CallAdvisor` 는 동기 전용. `StreamAdvisor` 별도 구현 필요 (Spring AI 1.0.0 기준) |
| LLM-as-Judge 비용 계산 | 2단계 보고서의 보강 후보 — 응답 검증을 별도 LLM 호출로 한다면 그 비용도 측정 |
| `@ControllerAdvice` 글로벌 에러 핸들러 | coderabbitai #2 잔여 — Observability 와 같은 레이어. 다음 작업으로 |
| AI 코드 리뷰 | quest 명세의 *"AI 가 만든 Spring AI 챗봇 코드의 프로덕션 결함 3개"* 식별 — 다음 작업으로 |
