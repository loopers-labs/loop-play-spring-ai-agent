# loop-play-spring-ai-agent

Spring AI 기반 배달 상담 에이전트 학습용 스타터 코드입니다.

루퍼스 부트캠프 "Spring AI 배달 상담 에이전트" 6주 과정 — Week 1 미션.
`ChatClient`, System Prompt, Structured Output, Streaming, Observability 개념을 실습합니다.

## 빠른 시작

```bash
./gradlew bootRun
```

---

## 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/support` | 배달 상담 1차 트리아지 |
| POST | `/api/v1/prompt-lab` | 프롬프트 정량 비교 실험 |

---

## 1주차 

### 시나리오 테스트

배달 위치 문의 · 취소 환불 · 음식 손상 — 3종 시나리오를 실제 API로 호출하고 응답을 검토했습니다.

- [support-triage.http](src/main/resources/evals/support-triage.http) — 요청 파일 (IntelliJ HTTP Client)
- [support-triage-responses.md](src/main/resources/evals/support-triage-responses.md) — 응답 JSON · 금지 규칙 준수 검토

### 실험 리포트

- [temperature-report.md](docs/week1/stage2/temperature-report.md) — temperature 0.0 / 0.3 / 0.7 sweep 결과 및 0.3 선택 근거
- [structured-prompt-comparison-report.md](docs/week1/stage2/structured-prompt-comparison-report.md) — 단순 vs 구조화 프롬프트 비교 결과
- [prohibition-ablation-report.md](docs/week1/stage2/prohibition-ablation-report.md) — [금지] 섹션 제거 시 공격 시나리오 응답 비교

### 설계 결정

- [design-decision-report.md](docs/week1/stage1/design-decision-report.md) — SYSTEM_PROMPT 금지 규칙 · Category enum · 추가 필드 선택 근거
