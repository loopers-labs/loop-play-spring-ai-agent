# 4단계: Observability + AI 코드 리뷰

**목표**: LLM에 실제로 전달되는 프롬프트와 토큰 수를 직접 관찰하고, AI가 만든 코드의 프로덕션 결함을 비판적으로 검토한다.

## 구현

- [x]  `PerformanceLoggingAdvisor` 구현 + `SupportController` 에 적용
- [x]  `/api/v1/support` 호출 후 콘솔 로그 기록

  ```
  2026-05-15T23:52:25.972+09:00  INFO 23108 --- [baedal-support-agent] [nio-8080-exec-6] c.b.support.PerformanceLoggingAdvisor    : LLM 호출 완료 — 9008ms | 입력 토큰: 632 | 출력 토큰: 92 | 총 토큰: 724
  ```

- [ ]  System Prompt 2배 실험

## AI 코드 리뷰

- [ ]  AI에게 "Spring AI로 배달 상담 챗봇을 만들어줘" 요청 후 받은 코드의 문제점 3개