# 4단계 — Observability + AI 코드 리뷰

> Memory 가 토큰 비용에 미치는 영향을 로그로 관찰하고(Observability), AI 가 생성한 멀티턴 챗봇
> 코드의 프로덕션 결함을 Round 3 자산으로 개선한다(AI 코드 리뷰).

## A. Observability — 10턴 토큰 증가 + Memory 주입 관찰

### 측정 조건

- `MAX_MESSAGES=20`, 기본 프로필(InMemory), temperature 0.3
- 한 세션에서 10턴 연속 대화 (`PerformanceLoggingAdvisor` 로그로 턴별 토큰 수집)
- 프롬프트 전문은 advisor 에 추가한 DEBUG dump 로 캡처

### 10턴 입력 토큰 증가

| 턴 | messages | inputTokens | outputTokens | 비고 |
|---:|---:|---:|---:|---|
| 1 | 2 | 3559 | 50 | Memory 비어있음 (SYSTEM + USER) |
| 2 | 4 | 3643 | 52 | Memory 가 1턴 주입 |
| 3 | 6 | 3711 | 61 | |
| 4 | 8 | 3790 | 95 | |
| 5 | 10 | 3904 | 74 | |
| 6 | 12 | 3995 | 61 | |
| 7 | 14 | 4075 | 86 | |
| 8 | 16 | 4090 | 43 | 윈도우 상한 근접 |
| 9 | 18 | 4093 | 72 | |
| 10 | 18 | 4090 | 26 | 정체 (≈4090) |

**관찰:**

- **입력 토큰이 3559 → 4093 으로 단조 증가** (10턴에 +534, 턴당 약 +53). Memory 가 이전 대화를
  프롬프트에 누적하기 때문 — *Memory 작동 = 입력 토큰 증가* 를 수치로 확인.
- **messages 가 2 → 18 로 증가**하다 T8~10 에서 토큰이 ~4090 으로 **정체**. `MAX_MESSAGES=20` 의
  슬라이딩 윈도우가 상한에 닿아 더 오래된 메시지를 잘라내기 시작한 신호. (2단계 MAX_MESSAGES 결론과 일치:
  윈도우가 차면 토큰이 평평해진다. 무제한이었다면 계속 증가했을 것.)
- **기저값 ~3500** 의 대부분은 system prompt + Tool 정의 JSON 이다 (Round 2 4단계 측정: system 70% +
  tools 28%). Memory 가 더하는 건 그 위의 증분 — *Memory 자체보다 system+tools 가 토큰의 주범* 이라는
  Round 2 결론이 여기서도 유효하다.

### 2회차 프롬프트 전문 — Memory 주입의 실물

1회차는 `messages=2`(SYSTEM + USER), 2회차는 `messages=4`. advisor DEBUG dump 로 본 2회차 프롬프트:

```
#0 [USER]      2024-1234 배달 어디쯤이에요?                    ← 1회차 USER  (Memory 가 주입)
#1 [ASSISTANT] 주문 2024-1234는 현재 배달 중이며 ... 약 20분 후 ← 1회차 ASSISTANT (Memory 가 주입)
#2 [SYSTEM]    [역할] 당신은 배달 서비스의 고객 상담 AI ...      ← system prompt
#3 [USER]      그거 몇 분 남았어요?                            ← 2회차 USER (이번 요청)
```

→ **2회차 요청에 1회차 대화(USER+ASSISTANT)가 그대로 들어간다.** "그거"가 1234 로 풀리는 건 마법이 아니라
이 주입의 결과다. 1단계에서 "Memory 검증과 Tool 검증은 별개"라 했는데, 그 *Memory 검증* 의 내부 동작이 이것.
(TOOL 메시지는 안 들어감 — `MessageChatMemoryAdvisor` 가 USER/ASSISTANT 만 적재, 3단계 스키마 관찰과 일치.)

## B. AI 코드 리뷰 (멀티턴 챗봇)

Codex 에 *순진한* 프롬프트(`"Spring AI 1.0으로 멀티턴 대화 챗봇 만들어줘. ChatMemory 써서..."`)를 던져
받은 코드를 분석한다. 우리 프로젝트 밖(`loop-play-spring-ai-agent-codex-round3`)에서 생성해 기존 구현을
참고하지 못하게 했다. 결과물: `ChatbotApplication` + `ChatbotConfig` + `ChatController` 3파일.

### 먼저 — Codex 가 잘한 점 (Round 2 GPT-5.5 보다 낫다)

정직하게, 이 코드는 Round 2 의 GPT-5.5 결과보다 완성도가 높다. 다음은 *이미 갖춘* 것들이다:

| 항목 | Codex 코드 | 의미 |
|---|---|---|
| 세션 분리 시도 | `request.conversationId()` → `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))` | 세션별 Memory 분리를 의도 |
| 크기 제한 | `MessageWindowChatMemory.builder().maxMessages(20)` | 무제한 누적 회피 (우리 2단계 교훈을 이미 반영) |
| 입력 검증 | `@Valid @NotBlank String message` | 빈 메시지 차단 |
| Builder 누적 회피 | `ChatClient` 를 `@Bean` 으로 1회 조립 후 주입 | **Round 2 의 "Multiple tools 누적" 함정을 안 밟음** |
| DTO | `record ChatRequest/ChatResponse` | 명확한 계약 |

→ "AI 생성 코드는 늘 엉망"이 아니다. 1년 전(Round 2 시점) 대비 모델이 *세션 분리·크기 제한* 같은
Spring AI 관용을 학습한 것으로 보인다. 그래서 **남은 결함은 더 미묘한, 운영에서야 드러나는 것들**이다.

### 결함 1 — 세션 ID `"default"` 폴백 (가장 심각, 우리가 실증한 사고)

```java
// ChatController.java
private static final String DEFAULT_CONVERSATION_ID = "default";

private String normalizeConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
        return DEFAULT_CONVERSATION_ID;   // ← 안 보낸 모든 클라이언트가 한 세션 공유
    }
    return conversationId.trim();
}
```

`conversationId` 는 요청 body 의 *옵션 필드* 라, 클라이언트가 생략하면 **전원이 `"default"` 세션을 공유**한다.
이것은 **우리 1단계 시나리오 5 에서 직접 재현한 개인정보 사고와 동일**하다 — 고객 A 가 ID 없이 주문번호를
말하면, ID 없이 들어온 고객 B 가 그 주문번호를 그대로 돌려받는다. "세션 분리를 시도했다"는 점에서 더
위험하다: 분리되는 것처럼 보여 테스트는 통과하고, ID 누락 클라이언트에서만 운영 중 터진다.

**개선 (우리 Round 3 자산)** — 식별자를 옵션 body 가 아니라 헤더로 받고, 누락 시 폴백이 아니라 거부:
```java
@PostMapping
public String ask(@Valid @RequestBody ChatRequest req,
                  @RequestHeader(value = "X-Session-Id", required = true) String sessionId) {
    // 개발 편의로 defaultValue="default" 를 쓰더라도, 프로덕션 프로파일에선 누락 시 400 으로 막아야 한다.
    // (우리도 같은 default 폴백을 쓰지만 시나리오 5 로 그 위험을 실증하고 문서화한 차이가 있다.)
```
→ 핵심은 *"폴백의 존재"가 아니라 "폴백의 위험을 알고 프로덕션에서 닫았는가"*. Codex 는 위험 인지 없이 열어뒀다.

### 결함 2 — InMemory 영속성 없음 (3단계 재시작 실험으로 입증)

```java
// ChatbotConfig.java
@Bean
ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();   // ← ChatMemoryRepository 미지정 = InMemoryChatMemoryRepository 기본
}
```

repository 를 지정하지 않아 기본 `InMemoryChatMemoryRepository` 가 쓰인다. **우리 3단계 재시작 실험에서
InMemory 는 재시작 후 4건→0건으로 전부 소실**됨을 확인했다. 멀티턴 챗봇인데 *배포 한 번에 모든 대화 맥락이
증발* — "이전 대화를 기억한다"는 이 앱의 목적 자체가 재시작에 무너진다.

**개선 (우리 3단계)** — JDBC 전환을 `@Profile` 로 분리하고, 우리가 겪은 함정 5종(자동구성 충돌 exclude /
`h2:file` / `initialize-schema: always` / `platform: postgresql`)을 적용. 단일 인스턴스 데모면 InMemory 도
정답이지만, *"앱 껐다 켜도 대화가 이어지길"* 기대하는 챗봇이라면 Q1(재시작 생존)에서 이미 JDBC 가 필요하다.

### 결함 3 — Observability 부재 (4단계 비용 맹점)

```java
.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())  // 토큰/시간 로깅 advisor 없음
```

Memory advisor 만 있고 토큰·응답 시간을 기록하는 수단이 없다. 그런데 **이 보고서 A 에서 본 대로, Memory 는
턴마다 입력 토큰을 누적시킨다(3559→4093).** 멀티턴 챗봇에서 이 누적이 곧 비용인데, Codex 코드는 그것을
*관측할 방법이 없다* — 운영에서 토큰 비용이 왜 오르는지 추적 불가.

**개선 (우리 4단계)** — `PerformanceLoggingAdvisor`(order=100)를 체인에 추가해 `inputTokens/elapsedMs` 를
턴별로 로깅. Memory 누적이 비용에 미치는 영향을 운영 대시보드로 끌어올린다.

### 종합

| 결함 | 우리 Round 3 실증 | 심각도 |
|---|---|---|
| 세션 `"default"` 폴백 | 1단계 시나리오 5 (대화 누출) | 높음 (개인정보) |
| InMemory 영속성 없음 | 3단계 재시작 4건→0건 | 높음 (UX 파손) |
| Observability 부재 | 4단계 토큰 3559→4093 추적 불가 | 중간 (운영 맹점) |

**가장 큰 교훈**: AI 생성 코드의 결함이 *문법 오류나 누락* 에서 *"운영에서야 드러나는 미묘한 판단"* 으로
옮겨갔다. 세션 분리를 "시도"했지만 폴백의 위험은 모르고, 크기 제한은 걸었지만 영속성·관측은 빠뜨렸다.
→ **이 결함들은 코드만 봐선 안 보이고, 우리가 1·3·4단계에서 직접 재현·측정했기에 짚을 수 있었다.**
*실패를 직접 만들어 본 사람만이 AI 코드의 진짜 위험을 리뷰할 수 있다* 는 게 이번 라운드 AI 리뷰의 결론이다.

## 학습 기록

→ Round 3 공통 학습 기록(내가 배운 것 / 의문점 / Round 4 아이디어)은 [README](../../../README.md) 의 *Round 3 — 공통 학습 기록* 참조.
