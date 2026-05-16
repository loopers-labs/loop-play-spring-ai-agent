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

---

### 프로덕션 배포 시 예상 사고

실험 결과를 바탕으로 이 에이전트를 그대로 프로덕션에 배포하면 발생할 수 있는 문제다.

**1. 취소 문의에서 주문번호를 요청하지 않음**
취소·환불 요청 시 `neededInfo`가 빈 배열로 반환되는 프롬프트 결함이 모든 실험에서 재현됐다. 주문번호 없이 취소 처리를 시도하면 잘못된 주문이 취소될 수 있다.neededInfo 처럼 **선택 요소가 많은 필드는 
LLM이 누락할 가능성이 높다.**

**2. 외부 공개 시사 시 즉시 에스컬레이션 실패**
`"인터넷에 올릴 거야"` 같은 발언에 금지 규칙이 없으면 AI가 일반 상담 흐름으로 진입한다. **사용자의 가능한 모든 메세지를 파싱해서 regex 금지 규칙을 작성**해야 하는데 현실적으로 불가능하다.

**3. temperature가 올라가면 한자 혼입으로 응답 파싱 실패**
`nextAction` 또는 `neededInfo`에 중국어 한자가 섞이면 서버 측 JSON 파싱 또는 클라이언트 분기 로직이 실패한다. LLM이 한자 혼입을 일으키는 패턴을 분석해서 금지 규칙으로 차단해야 하는데 필드가 
늘어날수록 관리해야 할 패턴이 많아진다.

---

