# loop-play-spring-ai-agent

루퍼스 부트캠프 "Spring AI 배달 상담 에이전트" 6주 과정의 학습 리포지토리입니다.
Week 1 부터 단계마다 코드 / 테스트 / 회고 docs 를 같이 묶고 있어요.
현재 Week 2 (Tool Calling) 까지 진행됐습니다.

## 빠른 시작

Ollama 와 `qwen2.5` 모델이 필요합니다.

```bash
ollama pull qwen2.5
./gradlew bootRun
```

기동되면 8080 에 네 엔드포인트가 열립니다.

```bash
# 1주차 — 트리아지 (구조화된 JSON 응답)
curl -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message":"어제 시킨 거 먹고 두드러기가 났어요."}'

# 1주차 — 프롬프트 정량 비교
curl -X POST http://localhost:8080/api/v1/prompt-lab \
  -H "Content-Type: application/json" \
  -d '{"systemPrompt":"...","message":"...","repeat":3}'

# 1주차 — 스트리밍 (SSE)
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'

# 2주차 — Tool Calling 에이전트
curl -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'
```

## 테스트

```bash
./gradlew test --rerun-tasks
```

JUnit XML 결과는 `build/test-results/test/*.xml` 에 떨어집니다. 최신 케이스 수와 통과 여부는 그 XML 또는 CI 결과 아티팩트에서 확인합니다.
`*ValidationTest` 클래스는 컨트롤러 경계의 Bean Validation 이 LLM 호출까지 흘러가기 전에 400 으로 차단되는지 보장하는 자리입니다.

## 1주차 회고 인덱스 — System Prompt / Structured Output / 정량 비교 / 스트리밍 / Advisor

각 회고 끝에는 "실측해보고 적어두는 부록" 이 붙어 있고, 07 이 그 데이터를 가로질러 봅니다.

- [00.들어가며.md](docs/1주차/00.들어가며.md) — 왜 지금 Agentic AI 인가, Spring AI 를 고른 이유
- [01.블로킹.md](docs/1주차/01.블로킹.md) — `.call()` 한 줄 위에서 본 블로킹 모델과 TTFT
- [02.생각정리.md](docs/1주차/02.생각정리.md) — 1주차 종합 회고
- [03.프롬프트설계.md](docs/1주차/03.프롬프트설계.md) — System Prompt + Structured Output (1단계)
- [04.프롬프트정량비교.md](docs/1주차/04.프롬프트정량비교.md) — categoryConsistency 와 그 한계 (2단계)
- [05.스트리밍구현.md](docs/1주차/05.스트리밍구현.md) — `Flux<String>` + SSE 직렬화 (3단계)
- [06.advisor와로깅.md](docs/1주차/06.advisor와로깅.md) — `CallAdvisor` + Usage 메타데이터 (4단계)
- [07.실측결과.md](docs/1주차/07.실측결과.md) — Ollama qwen2.5 로 두드린 전후 데이터

## 2주차 회고 인덱스 — Tool Calling / 멱등성 / description / Observability

1주차가 "잘 답하는 챗봇" 이었다면, 2주차는 그 위에 실제 주문 데이터를 만질 수 있는 능력을 얹는 라운드예요.
판단/실행의 경계, `@Tool` description, 멱등성 분기, View DTO — 네 가지 가드레일을 단계별로 두드렸습니다.

- [00.들어가며.md](docs/2주차/00.들어가며.md) — "판단은 LLM, 실행은 Spring Bean" 한 줄을 두고 잠깐 멈췄던 이유
- [01.도메인과Tool뼈대.md](docs/2주차/01.도메인과Tool뼈대.md) — Mock 도메인 / View DTO / `@Tool` 3개 (단계 1)
- [02.멱등성설계.md](docs/2주차/02.멱등성설계.md) — `Outcome` 4분기 / 분기 제거 실험 (단계 2)
- [03.description정량비교.md](docs/2주차/03.description정량비교.md) — description A/B/C 3 버전 비교 (단계 3)
- [04.observability와AI코드리뷰.md](docs/2주차/04.observability와AI코드리뷰.md) — Tool 왕복 로그 / Round 1 vs Round 2 토큰 / AI 생성 코드 리뷰 (단계 4)
- [05.생각정리.md](docs/2주차/05.생각정리.md) — 2주차 종합 회고 + 3주차로 넘기는 질문

## 실측 환경

회고의 "실측해보고 적어두는 부록" 들은 다음 환경에서 직접 두드린 데이터예요.

- Ollama `qwen2.5:latest` (4.7GB)
- `application.yml`: `temperature: 0.3`, `base-url: http://localhost:11434`
- `./gradlew bootRun` 으로 8080 에 띄운 후 `curl` 로 직접 호출
- raw 응답은 `/tmp/baedal-실측/` 에 저장 (커밋 대상은 아님)
