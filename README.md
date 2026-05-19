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
| POST | `/api/v1/chat/stream` | SSE 스트리밍 응답 |
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

### SSE 스트리밍

- [compare-sync-vs-streaming.md](docs/week1/stage3/compare-sync-vs-streaming.md) — 동기 vs 스트리밍 TTFT 측정 결과
- [streaming-vs-structured-output.md](docs/week1/stage3/streaming-vs-structured-output.md) — Structured Output에 스트리밍을 쓰면 안 되는 이유
- [streaming-frontend-changes.md](docs/week1/stage3/streaming-frontend-changes.md) — Streaming을 적용할 때 프론트엔드 아키텍처 변화 (상태 관리, 에러 처리, 인프라)

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

### 내가 배운 것

- **프롬프트 구조가 언어와 형식을 결정한다.** "당신은 배달 고객 상담 AI입니다" 한 줄짜리 프롬프트는 응답 언어를 지정하지 않으면 중국어·영어로 붕괴했다. JSON 형식도 나오지 않았다. 구조화 프롬프트(역할/규칙/금지/응답형식)가 있어야 안정적인 출력이 가능하다는 것을 실측으로 확인했다.
- **같은 필드라도 의사결정 구조가 다르면 temperature 민감도가 다르다.** `nextAction`은 선택지 중 하나를 고르는 단일 결정이라 temperature가 올라가도 큰 변화가 없었다. `neededInfo`는 독립적인 수집 항목이 여러 개 있는 구조여서 temperature가 높아질수록 누락이 늘었다. 출력 필드의 구조를 설계할 때 이 차이를 고려해야 한다.
- **키워드 기반 평가는 문맥을 읽지 못한다.** `"전화번호는 고객님에게 제공되지 않습니다"` — 개인정보를 거절한 올바른 응답인데 "전화번호는" 키워드가 포함됐다는 이유만으로 위반으로 판정됐다. 자동 평가 지표를 설계할 때 오탐을 항상 수동으로 확인해야 한다.

---

### 의문점

- **neededInfo 누락을 프롬프트로 해결할 수 있는가?** 취소·환불 케이스에서 주문번호를 요청하지 않는 결함이 모든 실험에서 재현됐다. 프롬프트에 규칙을 추가하면 해결될 것 같지만, 얼마나 명시적으로 써야 LLM이 안정적으로 따르는지 감이 없다.
- **키워드 평가 대신 LLM-as-judge를 쓰면 오탐이 줄어드는가?** 이번에 문맥을 읽지 못하는 키워드 평가의 한계를 직접 겪었다. LLM-as-judge라고 LLM이 응답을 평가하는 방식을 적용하면 되는 것인지 궁금하다.
- **골든셋 기반 평가에서 예측값을 미리 정의하는 방식이 LLM 평가에 적합한가?** 기대 출력을 사전에 정해두고 비교하는 방식에서는 temperature 0.0이 가장 좋게 나왔다. 그러나 LLM 응답은 정답이 하나가 아닌 경우가 많아서, 예측값과 다르다고 틀린 것으로 보는 평가 방식이 맞는지 의문이다.

---

