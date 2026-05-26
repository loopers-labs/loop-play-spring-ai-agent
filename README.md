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