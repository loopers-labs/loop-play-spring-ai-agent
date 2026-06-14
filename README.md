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
| Round 4 | RAG (검색 증강 생성) | PgVector(HNSW/COSINE) + `KnowledgeLoader` 시드(신규7/스킵7) + `QuestionAnswerAdvisor`(topK 4·threshold **0.45**·order 20) · 시나리오 5종 × 3회 · 설계결정 4종 정량화(청크 800/350 · Top-K 4 · advisor order · threshold sweep) + 청크 ablation(A/B/C end-to-end)·**오버랩 커스텀 splitter**(완전성 0→1) · 실패 관찰: *조각남*(B 자기모순)·*Fallback 가드 없는 환각*(가드 레이어드 제거→메뉴 환각) · **Fallback 과발동 수정**(QA 템플릿 재균형, 시나리오3 ✗3/3→○) · 발견: *RAG 병목은 검색 아닌 LLM* · *order는 검색 아닌 가시성·측정*(QA는 현재 턴 텍스트만 임베딩) · 4단계 Observability: RAG 주입 토큰 비용 3조건 비교(Memory✗RAG✗ 3211 = Memory만 3211 < Memory+RAG 4096, **RAG가 +885**) · 선택 4.4 **RAA**(CompressionQueryTransformer) 심화 — order 스왑 실측(memory먼저면 "2024-1234" 복원, RAA먼저면 미복원) | [docs/round-4/README.md](docs/round-4/README.md) |

