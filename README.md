# loop-play-spring-ai-agent

루퍼스 부트캠프 "Spring AI 배달 상담 에이전트" 6주 과정의 학습 리포지토리입니다.
Week 1 미션을 풀면서 단계마다 코드 / 테스트 / 회고 docs 를 같이 묶었어요.

## 빠른 시작

Ollama 와 `qwen2.5` 모델이 필요합니다.

```bash
ollama pull qwen2.5
./gradlew bootRun
```

기동되면 8080 에 세 엔드포인트가 열립니다.

```bash
# 트리아지 — 구조화된 JSON 응답
curl -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message":"어제 시킨 거 먹고 두드러기가 났어요."}'

# 프롬프트 정량 비교 — 같은 입력을 N번 반복해 안정성 측정
curl -X POST http://localhost:8080/api/v1/prompt-lab \
  -H "Content-Type: application/json" \
  -d '{"systemPrompt":"...","message":"...","repeat":3}'

# 스트리밍 — SSE 청크로 토큰 한 자씩
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'
```

## 테스트

```bash
./gradlew test --rerun-tasks
```

JUnit XML 결과는 `build/test-results/test/*.xml` 에 떨어집니다.
1주차 시점에 26 케이스가 같은 batch 에서 `failures="0" errors="0"` 으로 통과했어요.
`*ValidationTest` 클래스는 컨트롤러 경계의 Bean Validation 이 LLM 호출까지 흘러가기 전에 400 으로 차단되는지 보장하는 자리입니다.

## 회고 인덱스

1주차의 단계별 결정 흐름과 실측으로 검증된 부분을 회고로 분리해 정리했어요.
각 회고 끝에는 "실측해보고 적어두는 부록" 이 붙어 있고, 07 가 그 데이터를 가로질러 봅니다.

- [00.들어가며.md](docs/1주차/00.들어가며.md) — 왜 지금 Agentic AI 인가, Spring AI 를 고른 이유
- [01.블로킹.md](docs/1주차/01.블로킹.md) — `.call()` 한 줄 위에서 본 블로킹 모델과 TTFT
- [02.생각정리.md](docs/1주차/02.생각정리.md) — 1주차 종합 회고
- [03.프롬프트설계.md](docs/1주차/03.프롬프트설계.md) — System Prompt + Structured Output (1단계)
- [04.프롬프트정량비교.md](docs/1주차/04.프롬프트정량비교.md) — categoryConsistency 와 그 한계 (2단계)
- [05.스트리밍구현.md](docs/1주차/05.스트리밍구현.md) — `Flux<String>` + SSE 직렬화 (3단계)
- [06.advisor와로깅.md](docs/1주차/06.advisor와로깅.md) — `CallAdvisor` + Usage 메타데이터 (4단계)
- [07.실측결과.md](docs/1주차/07.실측결과.md) — Ollama qwen2.5 로 두드린 전후 데이터

## 실측 환경

회고의 "실측해보고 적어두는 부록" 들은 다음 환경에서 직접 두드린 데이터예요.

- Ollama `qwen2.5:latest` (4.7GB)
- `application.yml`: `temperature: 0.3`, `base-url: http://localhost:11434`
- `./gradlew bootRun` 으로 8080 에 띄운 후 `curl` 로 직접 호출
- raw 응답은 `/tmp/baedal-실측/` 에 저장 (커밋 대상은 아님)
