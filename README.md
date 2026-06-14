# loop-play-spring-ai-agent

Spring AI 기반 배달 상담 에이전트 학습용 스타터 코드입니다.

## 빠른 시작

```bash
./gradlew bootRun
```

---

## Round 1: ChatClient + Prompt Engineering + Streaming + Observability

**목표**: `ChatClient`, System Prompt, Structured Output, Streaming, Observability 개념을 실습한다.

| 단계 | 주제 | 문서 |
|---|---|---|
| 1단계 | 기본 API + System Prompt + Structured Output | [docs/round1/stage1.md](docs/round1/stage1.md) |
| 2단계 | Prompt Engineering 정량 비교 + 실패 관찰 | [docs/round1/stage2.md](docs/round1/stage2.md) |
| 3단계 | Streaming 응답 | [docs/round1/stage3.md](docs/round1/stage3.md) |
| 4단계 | Observability + AI 코드 리뷰 | [docs/round1/stage4.md](docs/round1/stage4.md) |

학습 기록 → [docs/round1/learning-notes.md](docs/round1/learning-notes.md)

---

## Round 2: Tool Calling

**목표**: `@Tool`을 작성하고 ChatClient에 등록해 LLM이 실제 주문 데이터를 조회·취소하게 만든다.

| 단계 | 주제 | 문서 |
|---|---|---|
| 1단계 | Tool 3개 구현 + Mock 데이터 확장 | [docs/round2/stage1.md](docs/round2/stage1.md) |
| 2단계 | 멱등성 관찰 — cancelOrder를 두 번 부르면 | [docs/round2/stage2.md](docs/round2/stage2.md) |
| 3단계 | Tool description 실험 | [docs/round2/stage3.md](docs/round2/stage3.md) |
| 4단계 | Observability + AI 코드 리뷰 | [docs/round2/stage4.md](docs/round2/stage4.md) |

학습 기록 → [docs/round2/learning-notes.md](docs/round2/learning-notes.md)

---

## Round 3: Chat Memory

**목표**: Memory 3레이어를 직접 조립하고, `X-Session-Id`로 고객별 세션을 분리하며, Memory 크기 실험과 InMemory ↔ JDBC 전환을 실습한다.

| 단계 | 주제 | 문서 |
|---|---|---|
| 1단계 | ChatMemory 3레이어 + X-Session-Id + 지시 대명사 시나리오 | [docs/round3/stage1.md](docs/round3/stage1.md) |
| 2단계 | Memory 크기 실험 + 실패 관찰 | [docs/round3/stage2.md](docs/round3/stage2.md) |
| 3단계 | InMemory vs JdbcChatMemory — 의사결정 트리 | [docs/round3/stage3.md](docs/round3/stage3.md) |
| 4단계 | Observability + AI 코드 리뷰 | [docs/round3/stage4.md](docs/round3/stage4.md) |

학습 기록 → [docs/round3/learning-notes.md](docs/round3/learning-notes.md)

---

## Round 4: RAG (Retrieval-Augmented Generation)

**목표**: PgVector + 임베딩 + `QuestionAnswerAdvisor`로 RAG 파이프라인을 구축하고, 청킹 전략 실험과 Memory·RAG 협업 동작을 관찰한다.

| 단계 | 주제 | 문서 |
|---|---|---|
| 1단계 | RAG 기본 구현 + 시나리오 5종 검증 + 설계 결정 문서 | [docs/round4/stage1.md](docs/round4/stage1.md) |
| 2단계 | 청킹 전략 실험 3조건 + 문맥 조각남 관찰 + Fallback 없는 환각 관찰 | [docs/round4/stage2.md](docs/round4/stage2.md) |
| 3단계 | Memory+RAG 협업 2턴 관찰 + Advisor 순서 뒤바꾸기 실험 | [docs/round4/stage3.md](docs/round4/stage3.md) |
| 4단계 | RAG 주입 토큰 관찰 + Context 블록 캡처 + AI 코드 리뷰 | [docs/round4/stage4.md](docs/round4/stage4.md) |

학습 기록 → [docs/round4/learning-notes.md](docs/round4/learning-notes.md)

---

## Round 5: Guardrail + Handoff + Fallback

**목표**: 다층 방어(Input/Output Guardrail)로 공격을 차단하고, 상담원 전환 트리거를 규칙 기반으로 판별하며, Tool/LLM 실패를 고객에게 안전하게 Fallback한다.

**Advisor 체인 순서**

```
InputGuardrailAdvisor(5) → MessageChatMemoryAdvisor(10) → QuestionAnswerAdvisor(20)
→ OutputGuardrailAdvisor(50) → PerformanceLoggingAdvisor(100)
```

| 단계 | 주제 | 문서 |
|---|---|---|
| 1단계 | InputGuardrailAdvisor + 공격 시나리오 5종 + Short-circuit 비용 증명 | [docs/round5/stage1.md](docs/round5/stage1.md) |
| 2단계 | OutputGuardrailAdvisor + SensitiveDataMasker + 마스킹/유출 시나리오 5종 | [docs/round5/stage2.md](docs/round5/stage2.md) |
| 3단계 | HandoffDetector + 상담원 전환 3종 + 규칙 기반 한계 관찰 | [docs/round5/stage3.md](docs/round5/stage3.md) |
| 4단계 | Fallback 처리 (Tool/LLM 실패) + AI 코드 리뷰 | [docs/round5/stage4.md](docs/round5/stage4.md) |

학습 기록 → [docs/round5/learning-notes.md](docs/round5/learning-notes.md)