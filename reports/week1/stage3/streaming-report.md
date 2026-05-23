# Streaming 응답 실험 결과

> Round 1 / 3단계 — `POST /api/v1/chat/stream` (SSE 기반) 구현. 첫 구현에서 `Structured Output ↔ Streaming` 충돌 직접 관찰 → `STREAMING_PROMPT` 분리로 수정 → 자연어만 흐르는 정상 동작 검증.

## 구현 흐름 — 발견 → 수정

### 1차 구현 (잘못된 자리)

```java
// SupportService (1차 — BaedalPrompt.SYSTEM_PROMPT 를 그대로 사용)
public Flux<String> streamSupportResponse(String message) {
    return chatClient   // ← SYSTEM_PROMPT(12필드 JSON 가이드) 적용된 client
            .prompt()
            .user(message)
            .stream()
            .content();
}
```

**증상 — SSE 응답에 raw JSON 텍스트가 그대로 노출**:

```text
data: summary
data: :
data:  고객이 주문번호와 배달 위치를 문의함.
data:
data: customerMessage
data: :
data:  "배송 중인 상태로, 현재 배달사가 어느 단계까지 진행되었는지 확인해 보겠습니다..."
data:
data: nextAction
data: :
data:  배달 위치 확인 후 안내
```

→ LLM 이 `SYSTEM_PROMPT` 의 JSON 스키마 가이드 따라 JSON 형식으로 응답 → `.stream().content()` 가 그 텍스트를 청크 단위로 흘림 → **`summary:`, `customerMessage:`, `nextAction:` 같은 키 텍스트가 사용자 화면에 노출**.

### 2차 구현 (수정 — `STREAMING_PROMPT` 분리)

`BaedalPrompt` 안에 두 system prompt 가 공유하는 가드레일(`CORE_GUARDRAILS`)을 추출하고, 용도별로 두 변형을 정의:

```java
public final class BaedalPrompt {

    /** 공유 가드레일 — [역할] / [규칙] / [금지]. */
    private static final String CORE_GUARDRAILS = """ ... """;

    /** Structured Output(JSON 12필드) 용 — `/api/v1/support`. */
    public static final String SYSTEM_PROMPT = CORE_GUARDRAILS + """
            [분류 가이드] ...
            [응답 작성 가이드] ...
            [정보 수집 가이드] ...
            [보상 처리 가이드] ...
            """;

    /** Streaming(자유 텍스트) 용 — `/api/v1/chat/stream`. */
    public static final String STREAMING_PROMPT = CORE_GUARDRAILS + """
            [응답 작성 가이드 - 자유 텍스트]
            고객에게 그대로 보낼 존댓말 응답을 자연어 한 단락으로 작성합니다.
            JSON 구조, 필드명, enum 값, 분류 정보, 라우팅 정보 등 내부 데이터는
            응답 텍스트에 절대 포함하지 않습니다.

            ★ 입력을 그대로 반복하지 말고 새 응답을 생성하십시오.
            ★ 부족한 정보가 있으면 자연스럽게 요청하십시오.
            ★ 불편 상황에는 공감 한 줄을 먼저 두십시오.
            ★ 보상·환불은 확정 표현 대신 "확인 후 안내드리겠습니다" 어구 사용.

            예 1) "주문번호 2024-1234 배달 어디쯤에 있어요?"
                 → "주문번호 2024-1234 배송 상태를 확인한 뒤 안내드리겠습니다."
            예 2) "방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"
                 → "주문 취소 가능 여부와 환불 소요 시간은 주문 상태 확인이 필요합니다. ..."
            예 3) "라이더가 음식을 엎었다는데 보상 받을 수 있나요?"
                 → "음식이 훼손되셔서 많이 속상하셨겠습니다. ..."
            """;
}
```

`SupportService` 는 두 개의 `ChatClient` 인스턴스 보유:

```java
public SupportService(ChatClient.Builder structuredBuilder, ChatClient.Builder streamingBuilder) {
    this.structuredChatClient = structuredBuilder.defaultSystem(BaedalPrompt.SYSTEM_PROMPT).build();
    this.streamingChatClient = streamingBuilder.defaultSystem(BaedalPrompt.STREAMING_PROMPT).build();
}
```

## 실험

입력: `"라이더가 음식을 엎었다는데 보상 받을 수 있나요?"`

### 실험 1 — 다른 prompt 호출 (첫 측정, 한 번)

| 호출 | system prompt | 총 시간 | TTFB |
|---|---|---:|---:|
| 동기 `/api/v1/chat` | **없음** (1단계 스타터) | 4 초 | 4 초 |
| 스트리밍 `/api/v1/chat/stream` | `STREAMING_PROMPT` | 7 초 | ~6 초 |

**문제 — 응답 내용이 완전히 다름** (다른 prompt 때문):
- 동기: *"죄송합니다, 현재 주문 정보를 확인할 수 있는 데이터가 없네요..."* (일반 챗봇)
- 스트리밍: *"음식이 훼손되셔서 많이 속상하셨겠어요. 주문번호와 상황을 알려주시면..."* (가이드 응답)

→ streaming 자체의 효과 vs system prompt 차이 효과를 분리 불가. **공정 비교를 위해 `ChatController` 도 `STREAMING_PROMPT` 적용으로 수정**.

### 실험 2 — 같은 prompt 호출 (수정 후, 한 번)

`ChatController` 가 `SupportService.chat()` 경유 → 동기·스트리밍 모두 `STREAMING_PROMPT` 사용.

| 호출 | 총 시간 | TTFB | 응답 |
|---|---:|---:|---|
| 동기 `/api/v1/chat` | **9 초** | 9 초 | *"음식이 훼손되셔서 많이 속상하셨겠어요. 주문번호와 상황을 알려주시면 확인 후 안내드리겠습니다."* (52자) |
| 스트리밍 `/api/v1/chat/stream` | **1 초** | <1 초 | *"음식이 훼손되셔서 많이 속상하셨겠습니다. 주문번호와 상황을 알려주시면, 확인 후 보상 가능 여부를 검토해 안내드리겠습니다."* (~70자) |

→ 응답 내용 매우 유사 (`STREAMING_PROMPT` 의 *공감 + 정보 요청 + 안내* 패턴). 공정 비교 성립.
→ **그러나 시간 차이 9초 vs 1초 — "streaming이 9배 빠르다?"** 한 번의 측정으로 결론짓기 위험. 변동성 확인 필요.

### 실험 3 — 변동성 측정 (각 5회 반복, 같은 prompt)

| 회차 | 동기 `/api/v1/chat` | 스트리밍 `/api/v1/chat/stream` | 차이 |
|---:|---:|---:|---:|
| 1 | 1.05s | 0.96s | +0.09s |
| 2 | 1.21s | 1.30s | -0.09s |
| 3 | 1.27s | 1.31s | -0.04s |
| 4 | 2.25s ← 이상치 | 0.99s | +1.26s |
| 5 | 1.12s | 0.99s | +0.13s |
| **평균** | **1.38s** | **1.11s** | **+0.27s** |
| **min~max** | 1.05~2.25 (변동폭 1.20s) | 0.96~1.31 (변동폭 0.35s) | |

**중요한 발견**:
1. **실험 2의 9초/1초 차이는 *cold start* 였다** — 첫 호출이 LLM warm-up + KV cache miss 비용 부담. 두 번째 호출부터는 매우 빠름
2. **warm 상태에서는 동기·스트리밍 모두 1초 전후** — 평균 차이 0.27s, 통계적으로 미미
3. **동기의 변동폭이 더 큼** (1.20s vs 0.35s) — LLM 응답 시간 자연 변동 + 4회차 이상치
4. **응답 자체가 약 1초로 짧아** streaming 의 부분 출력 효과가 발휘될 시간이 없음 → 청크가 끝나기 전에 응답 완료

## 관찰

### 1. ⚡ `STREAMING_PROMPT` 분리가 핵심 결정

| 시점 | 코드 | 응답 | 사용자에게 표시 가능? |
|---|---|---|---|
| 1차 (`SYSTEM_PROMPT` 적용) | `chatClient.prompt().user().stream().content()` | `data: summary` `data: :` ... raw JSON | ❌ — 내부 구조 노출 |
| 2차 (`STREAMING_PROMPT` 적용) | `streamingChatClient.prompt().user().stream().content()` | `data: 음` `data: 식` ... 자연어만 | ✅ — 그대로 화면 표시 |

→ **streaming 의 시스템 프롬프트는 *자유 텍스트 응답* 만 유도해야 한다.** Structured Output 가이드와 분리 필수.

### 2. `CORE_GUARDRAILS` 공유로 일관성 확보

`[역할]`, `[규칙]`, `[금지]` 가 두 system prompt 에 공통 적용:
- `[금지]` 8개 항목(개인정보·보상 약속·경쟁사 비교 등)이 streaming 응답에도 그대로 적용
- 한 번 수정하면 두 엔드포인트에 동시 반영 (DRY)
- 2단계 ablation 보고서 결론(*"진짜 가드레일은 적절한 구조"*) 이 코드 구조로 실현

### 3. 체감 속도 — 측정 환경에 따라 streaming 의 가치가 크게 달라진다 (3회 측정으로 확인)

실험 1·2·3 종합:

| 환경 | streaming의 시간 이득 | 측정 결과 |
|---|---|---|
| **Cold start** (첫 호출, LLM warm-up 미진행) | **큼** | 실험 2 — 동기 9초 vs 스트리밍 1초 (9배 차이) |
| **Warm 상태** (KV cache 적중) + 짧은 응답 | **거의 없음** | 실험 3 — 평균 1.38s vs 1.11s (0.27s 차이) |
| Warm + 긴 응답 (가정) | **있음 (예상)** | 본 실험 안 함. 긴 응답이면 누적 출력 효과 |
| 큰 모델 (GPT-4·Claude) | 다를 가능성 | 본 실험은 `qwen2.5` 로컬 한정 |

**핵심 교훈 — *"한 번의 측정으로 결론짓지 말 것"***:
- 실험 2: 1회 측정 → *"streaming 이 9배 빠르다"* (오해 가능)
- 실험 3: 5회 반복 → *"warm 상태에서는 거의 같다, cold 가 다른 거였다"* (실제)

`temperature: 0.3` 비결정성 + LLM 호출 시간 자연 변동 → **N회 평균 + 변동폭** 함께 보고가 필수.

→ **streaming 의 본질적 가치는 *"부분 출력을 사용자가 진행 중인 상태로 볼 수 있다"* 자체** 에 있다. 그러나 *시간 이득* 으로 이어지려면 (1) cold start 가 있거나, (2) 응답이 충분히 길거나, (3) TTFB 가 충분히 짧아야 함. **본 환경(warm + 짧은 응답)에서는 시간상 이득 거의 없음**.

**즉, *"streaming 이면 무조건 빠르다"* 는 거짓.** 응답 길이·모델 상태(cold/warm)·KV cache 효과·setup overhead 의 함수.

### 4. 토큰 단위의 정확한 의미

응답이 한 *글자* 단위가 아니라 LLM **토큰** 단위로 옴. qwen2.5 의 한국어 토큰화 예시:
- `"고객"`, `"이"`, `" 배"`, `"달"`, `" 위치"`, `"를"`, `" 문"`, `"의"`, `"함"`, `"."`

자주 쓰이는 한국어 단어는 1토큰, 덜 쓰이는 단어는 글자 단위로 쪼개짐. 더 큰 모델(GPT-4·Claude)일수록 더 큰 단위로 묶임. **체감 효과는 *글자가 흐르는 듯* — 모델·언어·단어 빈도에 따라 글자 단위 ~ 단어 단위 사이**.

### 5. Streaming 의 적용 범위 결정

| 케이스 | 적용 가능? | 사용 system prompt |
|---|---|---|
| **자유 텍스트 챗봇** (현재 `/api/v1/chat/stream`) | ✅ | `STREAMING_PROMPT` |
| **Structured Output JSON 응답** (`/api/v1/support`) | ❌ | streaming 적용 X. 동기 `.call().entity()` 유지 |
| **상담사·운영자 화면용** 긴 정책 안내 | ✅ (가정) | `STREAMING_PROMPT` 또는 그 변형 |

### 6. 프론트엔드 영향

| 항목 | 동기 (`/api/v1/support`) | 스트리밍 (`/api/v1/chat/stream`) |
|---|---|---|
| HTTP 클라이언트 | `fetch()` / `axios` | `EventSource` (GET 만) 또는 `fetch + ReadableStream` (POST) |
| 응답 처리 | JSON.parse → 객체 | 청크 누적 (`onmessage` 이벤트마다 텍스트 추가) |
| UI 패턴 | 로딩 스피너 → 응답 출현 | 타이핑 효과 (커서 + 글자 점진 추가) |
| 응답 형식 | JSON 객체 (12필드) | `data: ...` 라인의 자연어 청크 |
| 취소 | request abort | `EventSource.close()` |

POST + SSE 는 `EventSource` API 가 지원 안 함 → `fetch + ReadableStream` 패턴 또는 `@microsoft/fetch-event-source` 같은 라이브러리 사용.

### 7. Streaming 은 신뢰성 가드레일이 아니다 (2단계 결론과 연결)

2단계 ablation 보고서 결론: *"진짜 사고는 `nextAction` 의미 위반 + `routing=AUTO` 자동 처리 조합에 있다"*.

streaming 은 **고객 화면의 UX 변화**일 뿐 신뢰성 가드레일과는 별개 축:
- streaming 적용해도 LLM 응답 자체의 위반 가능성(보상 약속·개인정보 노출 등)은 동일
- 우리 `STREAMING_PROMPT` 의 `CORE_GUARDRAILS` 공유가 그래서 중요 — `[금지]` 가드레일이 streaming 응답에도 적용
- 다만 streaming 응답은 분류·라우팅 정보 없음 → 후속 자동 처리는 동기 엔드포인트(`/api/v1/support`)에서 별도로 수행되어야

## 결론

### 핵심 발견

1. **`Structured Output` 과 `Streaming` 은 한 system prompt 로 같이 못 씀** — JSON 청크 분리 시 raw 텍스트 노출. 두 용도용 system prompt 분리가 필수
2. **`CORE_GUARDRAILS` 공유 패턴** — `[역할]`/`[규칙]`/`[금지]` 를 두 prompt 에 공통 주입하면 가드레일 일관 + DRY
3. **체감 속도는 응답 길이에 비례** — 짧은 응답에는 미미, 긴 응답에 가치 큼
4. **Streaming 은 UX 개선 축**, 가드레일은 별도 축 (system prompt + 라우팅)
5. **토큰 단위 ≠ 글자 단위** — LLM 토큰은 모델·언어에 따라 1글자 ~ 한 단어. 체감은 *"흐르는 글자"* 비유

### 1단계 학습 기록 의문과의 연결

본인의 1단계 학습 기록 의문 *"AI 시스템을 어떻게 신뢰?"* 의 구체적 답이 streaming 구현 자리에서도 동일하게 적용됨:
- *메트릭 신뢰 X* (2단계 발견)
- ***적절한 구조 설계*** (3단계 발견 — 두 prompt 분리, CORE_GUARDRAILS 공유)
- *사람 인계 라우팅* (1단계 설계의 `*_REVIEW`)

세 라운드의 결론이 같은 방향 — **AI 신뢰는 구조에서 온다**.

### 후속 과제

| 항목 | 내용 |
|---|---|
| 긴 응답 실험 | 본 실험은 응답 약 100~200자. *"환불 정책 전체 안내"* 같이 긴 응답에서 다시 측정하면 streaming 가치가 더 명확 |
| `STREAMING_PROMPT` 의 시나리오별 예시 추가 | 현재 3개 예시(시나리오 1·2·3). 모호 케이스(S4) 같은 예시 추가하면 echo 차단 일반화 가능 |
| 4단계 Observability 연결 | streaming 호출의 토큰 수·응답 시간을 advisor 로 측정하면 동기와 비용 비교 가능 |
| Two builder injection 검증 | Spring AI `ChatClient.Builder` 가 두 번 주입 시 fresh 인스턴스를 주는 것으로 본 실험에서 검증됐으나, 다른 Spring 버전·환경에서도 같은 동작인지 확인 |

---

## 확장 — SSE 에 분류 메타데이터 추가 (옵션 A: streaming + 마지막 한 번 meta)

### 동기

기본 streaming 응답은 `customerMessage` 자연어만 흐름. 그러나 운영 시스템은 `category` / `recommendedRouting` / `missingInfo` 같은 **메타데이터** 도 필요:
- `recommendedRouting=AGENT_REVIEW` 면 상담사 인계 처리
- `missingInfo=["orderNumber"]` 면 추가 정보 요청 UI 표시
- `category=CLAIM` 이면 보상 검토 워크플로우 분기

→ 사용자 화면은 streaming 으로 빠른 응답, 시스템은 마지막에 메타데이터 받아 후속 처리.

### 구현 — SSE event type 으로 청크 종류 구분

`Flux<String>` → `Flux<ServerSentEvent<String>>` 로 변경:

```java
public Flux<ServerSentEvent<String>> streamSupportWithMetadata(String message) {
    Flux<ServerSentEvent<String>> tokens = streamingChatClient
            .prompt().user(message).stream().content()
            .map(chunk -> ServerSentEvent.<String>builder().event("token").data(chunk).build());

    Mono<ServerSentEvent<String>> meta = Mono.fromCallable(() -> {
        SupportResponse classified = generateSupportResponse(message);   // SYSTEM_PROMPT 동기 호출
        return ServerSentEvent.<String>builder()
                .event("meta")
                .data(objectMapper.writeValueAsString(classified))
                .build();
    }).subscribeOn(Schedulers.boundedElastic());

    return Flux.concat(tokens, meta);   // streaming 끝나면 meta 한 번
}
```

### 검증 결과

| 시간 | 청크 종류 | 내용 |
|---:|---|---|
| 0~9s | `event: token` × 31회 | `음`, `식`, `이`, ` 훼`, `손`, ... — 자연어 streaming |
| 19s | **`event: meta`** | 12필드 JSON 한 번에 |

`event: meta` 실제 응답:
```json
{
  "summary": "고객이 음식 훼손에 따른 보상 가능 여부를 문의함.",
  "customerMessage": "음식이 훼손되셔서 많이 속상하셨겠습니다...",
  "category": "CLAIM",
  "intent": "CLAIM_DAMAGED_FOOD",
  "recommendedRouting": "AGENT_REVIEW",
  "missingInfo": [],
  ...
}
```

### Trade-off

| 항목 | 영향 |
|---|---|
| 사용자 화면 | 8초 후 답 표시 시작 (`event: token`) + 19초 후 자동 처리 가능 (`event: meta`) |
| **LLM 호출** | **2회** (streaming + structured) → **비용 ×2** |
| 총 시간 | streaming 9초 + 메타데이터 호출 10초 (cold start 영향. warm 이면 더 빠름) |
| 운영 가치 | streaming UX + 1단계 12필드 메타데이터 모두 확보 |

### 프론트엔드 구현 패턴

```javascript
eventSource.addEventListener('token', (e) => {
  appendToMessage(e.data);   // 자연어 누적 표시
});
eventSource.addEventListener('meta', (e) => {
  const meta = JSON.parse(e.data);
  if (meta.recommendedRouting === 'AGENT_REVIEW') showEscalationButton();
  if (meta.missingInfo.includes('orderNumber')) showInfoPrompt();
});
```

### 대안 옵션 (구현 안 했지만 분석)

| 옵션 | 호출 수 | UX | 메타데이터 정확도 | 구현 난이도 |
|---|---|---|---|---|
| **A (현재 구현)** — streaming + 마지막 meta 동기 호출 | LLM ×2 | streaming 즉시 | 1단계 12필드 정확 | 낮음 |
| B — streaming 후 customerMessage 재분석 | LLM ~1.x | streaming 즉시 | 정보 손실 (입력 컨텍스트 X) | 중간 |
| C — LLM 응답 첫 줄에 JSON, 나머지 자연어 | LLM ×1 | 첫 JSON 청크 대기 | 짧은 JSON 만 가능 | 중간 |

→ **본 옵션 A 는 정확도·UX 우선, 비용 ×2 trade-off**. 운영 시 비용이 부담스러우면 옵션 C 로 전환 검토.
