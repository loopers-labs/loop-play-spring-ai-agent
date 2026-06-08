# loop-play-spring-ai-agent

Spring AI 기반 agent 동작을 직접 돌려보고 관찰·기록하는 학습 저장소.
주된 산출물은 `docs/` 아래 주차별 실험 리포트와 회고이며, 코드는 실험 도구다.

## 작업 원칙

- 학습 과정을 가리는 자동화는 쓰지 않는다. autopilot / ralph / ultrawork / team / conductor 류 멀티스텝 워크플로는 이 저장소에서는 호출하지 않는다.
- 실험 결과를 단정하기 전에 raw 로그 또는 재현 명령을 함께 남긴다. 추측으로 결론짓지 않는다.
- 문서는 강의 회고체 템플릿(📌 / 메타정리 / 깔끔구조 불릿) 복붙 금지. 1인칭 판단 흐름으로 쓴다.

## 우선 사용할 skill

다음 3개는 이 저장소의 작업 맥락과 직접 맞물려 있어 해당 상황이 오면 먼저 고려한다.

기본 흐름은 `experiment-observation` -> `writing-input-check` -> `technical-writing` 이다.
다만 이미 실험 근거가 충분하면 `writing-input-check` 부터, 글쓰기 brief 도 충분하면 `technical-writing` 만 쓴다.

- `experiment-observation` — 실험 질문을 잡고, 비교 조건 / 반복 횟수 / 관측 신호 / raw 저장 위치 / 해석 한계를 정할 때.
  prompt 비교, Tool Calling 라우팅, streaming, advisor 로그, token/latency 관측은 결론부터 쓰지 말고 이 skill로 실험 모양을 먼저 잠근다.
  완료 조건은 바꾼 변수 / 고정 조건 / 관측 신호 / raw 위치 / 관측-추정-한계 분리가 명확한 상태다.

- `writing-input-check` — 글을 쓰기 전에 독자 / 출력 형태 / 핵심 한 문장 / raw 근거 / 단정 금지선 / 톤이 비어 있는지 확인할 때.
  초안부터 뽑고 "아니 그게 아니라" 로 왕복하지 않도록, 부족한 고충격 입력만 1~3개 질문으로 채운 뒤 `technical-writing` 으로 넘긴다.
  최소 brief 는 독자 / 출력 형태 / 핵심 한 문장 / 근거 상태 / 단정 금지선이다.

- `technical-writing` — `docs/` 아래 회고·실험 리포트·블로그 초안을 쓰거나 고칠 때.
  AI톤 회피와 톤 일관성 유지를 위해 글쓰기 작업은 이 skill을 통과시킨다. 실험 설계가 아직 비어 있으면 `experiment-observation` 을, 글쓰기 입력이 비어 있으면 `writing-input-check` 를 먼저 쓴다.
  완료 조건은 강한 주장에 근거가 붙고, 관측/추정/의견이 섞이지 않으며, 남은 불확실성이 보존된 상태다.

위 셋 외 skill은 명시 요청이 있을 때만 사용한다.

## 디렉터리 메모

- `docs/` — 주차별 실험 리포트와 회고. 실측 raw 로그는 `docs/<주차>/실측-raw/`.
- `src/` — 실험용 Spring AI 코드. 운영 코드가 아니라 실험 매개체.
