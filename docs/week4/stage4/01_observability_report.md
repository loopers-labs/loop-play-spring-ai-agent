# 4단계 Observability — RAG 주입 토큰 비용 관찰 리포트

## 목적

- RAG가 주입하는 토큰 비용을 `PerformanceLoggingAdvisor`/`PerCallObservationHandler` 로그로 직접 관찰한다.
- 동일 질문을 Advisor 체인 3조건(a/b/c)에서 1회씩 보내 입력 토큰을 대조한다.

## 결론

- **RAG는 입력 토큰을 +902 늘린다** (3034→3936, 약 +30%). 증가분의 실체는 user 메시지에 삽입된 refund FAQ 원문 2건이다.
- **빈 Memory는 비용 0**: (b) 입력이 (a)와 정확히 동일(3034). Memory 토큰 비용은 대화가 쌓인 뒤부터 발생한다.
- **RAG는 비용만큼 값을 한다**: RAG 없는 (a)/(b)는 "상담사 연결"로 회피했지만, (c)는 정책 원문(24시간·메뉴 누락·오배송)을 정확히 인용했다. +902 토큰이 답변 정확도를 산다.
- 운영 관점: RAG 토큰 비용은 TOP_K·유사도 임계·청크 크기로 직접 통제된다(현재 TOP_K=4·임계 0.5에서 2건 삽입). 응답 시간(ms)은 로컬 LLM 부하로 실행마다 갈리는 값이라 비용 지표로 쓰지 않는다.

## 실험 설정

- 질문(고정): `배달 완료 후에도 환불 받을 수 있나요?`
- 모델: chat `qwen2.5:14b`, embedding `qwen3-embedding:0.6b` (`application.yml`)
- 프로필: `dev` (wire 로그 `[Ollama 요청]` 활성 — Context 원문 캡처용)
- 조건 전환: `AssistantController.java:66` `.defaultAdvisors(...)`만 수정, 매 조건마다 서버 재기동(Memory 초기화)
- cases: [`observability/cases.json`](observability/cases.json), 러너 `docs/verification_memory/run_memory_scenarios.sh`

| 조건 | Advisor 체인 |
| --- | --- |
| (a) Memory 없음 + RAG 없음 | `performanceAdvisor` |
| (b) Memory만 | `memoryAdvisor, performanceAdvisor` |
| (c) Memory + RAG | `memoryAdvisor, ragAdvisor, performanceAdvisor, SimpleLoggerAdvisor` (원본) |

## 정량 비교표

- 출처: 각 조건 `server.log`의 `[PERF]`(왕복 합계)·`[LLM #1]`(호출별). 총호출은 모두 1회(Tool 미호출).

| 조건 | 입력 토큰 | 출력 토큰 | 응답 시간(ms) | 비고 |
| --- | --- | --- | --- | --- |
| (a) Memory 없음 + RAG 없음 | 3034 | 29 | 20682 | 정책 정보 없어 상담사 연결 응답 |
| (b) Memory만 | 3034 | 49 | 2451 | 빈 Memory → (a)와 입력 토큰 동일 |
| (c) Memory + RAG | 3936 | 221 | 17127 | RAG Context 블록(refund 2건) 포함 |

- **RAG 주입으로 입력 토큰 +902** (3034 → 3936, 약 +29.7%).
- (b)의 입력 토큰이 (a)와 **정확히 같음**(3034) → 빈 Memory는 토큰을 추가하지 않음. Memory 비용은 대화가 쌓여야 발생.
- 입력 증가의 실체는 user 메시지에 삽입된 정책 원문(아래 Context 블록).
- 응답 시간(ms)은 로컬 LLM 부하·KV 캐시 상태에 따라 실행마다 갈리는 관찰값이라 토큰 비교의 보조 지표로만 본다. (a)가 20682ms로 큰 것은 콜드 스타트 영향.
- 출력 토큰은 (c)에서 29→221로 급증 → Context를 받은 LLM이 실제 정책을 구체적으로 인용했기 때문.

## Context 블록 원문 캡처 (조건 c)

- 캡처 위치: `observability/responses/cases/c_server.log`의 `[Ollama 요청]` → user role content.
- 전문: [`observability/responses/cases/c_context_block.txt`](observability/responses/cases/c_context_block.txt)
- 헤더 형식은 Spring AI `QuestionAnswerAdvisor` 기본 템플릿(`Context information is below ... Given the context...`). `Context:` 단일 헤더가 아님.
- 삽입된 FAQ 문서 **2건** (TOP_K=4, 유사도 임계 0.5 — knowledge/ refund 카테고리):

| 순서 | 문서 | 핵심 내용 |
| --- | --- | --- |
| 1 | `refund__refund-after-delivered.md` | 배달 완료 후 환불 사유(메뉴 누락·오배송·품질 불량·수량 오류), 24시간 시한, 증빙 |
| 2 | `refund__refund-basic.md` | 환불 가능/불가 케이스, 카드 최대 7영업일, 60분 지연 보상 |

캡처된 user 메시지 구조:

```
배달 완료 후에도 환불 받을 수 있나요?

Context information is below, surrounded by ---------------------

---------------------
# 배달 완료 후 환불 정책
... (refund-after-delivered 전문)
# 환불 기본 정책
... (refund-basic 전문)
---------------------

Given the context and provided history information and not prior knowledge,
reply to the user comment. If the answer is not in the context, inform
the user that you can't answer the question.
```

## 응답 비교 (동작 검증)

- (a)/(b): RAG 없음 → "정확한 답변이 어렵습니다. 상담사에게 연결" (정책 모름 → 안전하게 에스컬레이션).
- (c): Context의 `refund-after-delivered` 원문을 그대로 인용 — 메뉴 누락·오배송·품질 불량·수량 오류 + 24시간 시한.

## 산출물

- `observability/responses/cases/{a,b,c}_server.log` — 조건별 전체 서버 로그(`[PERF]`/`[LLM #1]`/wire 로그)
- `observability/responses/cases/{a,b,c}_step1.json` — 조건별 응답 본문·http·시간
- `observability/responses/cases/c_context_block.txt` — 조건 c의 Context 블록 전문

## 검증

- 코드 변경 0: 실험 후 `git status src/` 클린(원본 체인 복원 확인).
- 3조건 모두 http=200.
