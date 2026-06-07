# loop-play-spring-ai-agent

Spring AI 기반 배달 상담 에이전트 학습용 스타터 코드입니다.

## 개요

루퍼스 부트캠프 "Spring AI 배달 상담 에이전트" 6주 과정의 Week 1 미션 스타터 코드입니다.
`ChatClient`, System Prompt, Structured Output, Streaming, Observability 개념을 실습합니다.

## 빠른 시작

```bash
./gradlew bootRun
```

## 테스트

이 README는 PR 워크플로우 검증용 테스트 커밋입니다.


## 라운드별 작업 내역

| Round   | 주제 | 핵심 산출물 (한 줄) | 본문                                               |
|---------|---|--|--------------------------------------------------|
| Round 1 | 기본 API · Prompt Engineering · Streaming · Observability | [규칙]/[금지] 다층 설계 · prompt-lab 정량 비교 · SSE TTFB/Total 측정 · PerformanceLoggingAdvisor + AI 코드 리뷰 | [docs/round-1/README.md](docs/round-1/README.md) |
| Round 2 | Tool Calling | Tool 3개 + Mock 6건 · Outcome 4가지(멱등) · description 7 변형 + 충돌 실험 · BaedalPrompt `[Tool 사용 규칙]` 사후 실험 (*Goodhart 가설*) | [docs/round-2/README.md](docs/round-2/README.md) |
| Round 3 | 대화 맥락 관리와 메모리 설계 | ChatMemory 3레이어 + `X-Session-Id` 세션 격리 · 지시 대명사 5종 × 3회 재현율 · `MAX_MESSAGES` 2/20/∞ 토큰 실험(cap=메시지 수 ≠ 토큰) · InMemory↔JDBC 투명 교체 + 재시작 영속성 + 의사결정 트리 · *언어 드리프트 ⊥ 메모리* 발견 | [docs/round-3/README.md](docs/round-3/README.md) |

