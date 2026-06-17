# JOURNAL — Spring AI Agent 학습 회고

> 주차별 작업 히스토리 요약과 개인 회고/의견을 누적 기록한다.
> 상세 작업 기록·과제 원문은 `.private/` 에 비공개 보관하며, 이 문서에는
> 정제된 히스토리와 개인 의견만 남긴다. (git에 포함되는 유일한 기록 문서)

## Round 1 — Spring AI 기반 배달 상담 챗봇 만들기

- 기간: 2026-05-13 ~ 2026-05-16
- 한 일: System Prompt 가드레일 4종, `SupportResponse` 11필드 Structured Output, `PromptLabController`로 정량 비교, 동기 vs 스트리밍, `PerformanceLoggingAdvisor`, AI 코드 리뷰.
- 배운 점 / 개인 의견:
  - 가드레일·구조화·스트리밍을 트레이드오프로 보게 된 게 컸다. \"AI 시대에도 우리는 여전히 비즈니스 가치를 위해 트레이드오프를 한다\"는 감각.
  - `@JsonPropertyDescription` 같은 스키마 층이 System Prompt 길이보다 분류 안정성에 더 결정적이라는 측정 결과.
- 막힌 점 / 해결: AI 코드 리뷰 단계에서 \"진짜 결함\"이 보안·접근통제(IDOR, conversationId 탈취)에 있다는 걸 깨닫는 데 시간이 좀 걸림. 표면 결함(키 하드코딩 등) 위주로 보다가 늦게 발견.

## Round 2 — Tool Calling으로 주문/배달 시스템 연동

### 1단계 — Tool 3개 구현 + Mock 데이터 확장

- 기간: 2026-05-23 ~ 2026-05-24
- 한 일:
  - `OrderTools`에 `getOrderDetail`/`getDeliveryStatus`/`cancelOrder` 세 `@Tool` 구현. description은 4요소(무엇/언제/입력/실패) + detail/delivery 비대칭(delivery가 모호 발화 기본값, detail은 구체 키워드만)으로 작성.
  - `OrderMockService`에 시드 4건 추가(1236 DELIVERED / 1237 COOKING / 1238 사전 CANCELED / 1239 ACCEPTED). 1238은 `cancel()` 호출로 `canceledReason`까지 채워서 2단계 멱등성 실험 전제 준비.
  - `AssistantController`·`SupportController` 모두 생성자에서 `ChatClient` 한 번만 빌드해 `.defaultTools(orderTools)` 등록 — 강의 2.5.1 \"흔한 함정\"(매 요청 builder.defaultXxx 누적) 회피.
  - 시나리오 5종 × 10회 × 5 baseline = **250 trial 데이터**를 raw JSONL로 보존(`.private/notes/round2/quest1-v{1..5}-*.jsonl`). 분석은 `EXPERIMENT_LOG.md` 참조.
- 배운 점 / 개인 의견:
  - **단발 측정의 함정**. 첫 단발 테스트로는 \"40% 호출\"이었는데 10회 반복 평균은 84%. 신뢰구간 없는 단발은 의미 없다.
  - **System Prompt 만지작거리지 말기**. v2~v5 다섯 번 변형, 다섯 번 회귀. 정책 한 줄이 description 효과를 가린다는 강의 메시지를 데이터로 본 게 가장 컸음.
  - **`@ToolParam` 예시는 hallucination fallback이 된다**. scn 4 9/9가 동일 가짜 사유로 호출된 패턴은 단순 호출률 지표로는 못 잡는 데이터 품질 버그. 운영 환경에선 더 무서울 듯.
  - **\"안 되는 거 알면서 계속\" 안티패턴**을 학습. 5번째 회귀 시점에 멈추고 v1으로 원복하는 결정. 가설 부정 결과 자체가 강한 증거.
- 막힌 점 / 해결:
  - 가장 약한 곳(scn 2의 5/10)을 잡으려는 시도가 다 회귀로 끝남. 처음에는 \"부정 조건이 문제일 것\"이라고 추정했으나(v3 가설), 부정 제거가 더 떨어뜨리며 기각. 그 다음 \"`@ToolParam` 예시 hallucination을 sentinel로 잡으면 개선될 것\"(v4) 도 기각. \"트리거 문구를 좁히면\"(v5)도 기각. 결국 *현재 setup에서 84%가 사실상 상한*이라는 결론에 도달.
  - 결론: 더 끌어올리려면 System Prompt 외의 다른 lever(모델 자체, description 구조, Tool 등록 순서 등)가 필요. 1단계 범위에서는 84%를 baseline으로 인정하고 매듭.

### 2단계 — 멱등성 관찰

- 기간: 2026-05-24 ~ 2026-05-25
- 한 일:
  - `cancelOrder`의 Outcome 4종 (`CANCELED`/`ALREADY_CANCELED`/`NOT_CANCELABLE`/`NOT_FOUND`) 50회 측정.
  - 멱등성 분기 제거 실험을 *2단계*로 확장: Stage A (`ALREADY_CANCELED` 제거) + Stage B (`ALREADY_CANCELED` + `NOT_CANCELABLE` 둘 다 제거). 각 10 pair × 2회 cancel = 80 trial.
  - 측정 편의를 위해 `TestResetController` (`/api/v1/_internal/reset`) 임시 추가, `OrderMockService.resetForTest()` 도입.
- 배운 점 / 개인 의견:
  - **NOT_CANCELABLE이 *두 번째 안전망*** — Stage A에서 `ALREADY_CANCELED` 제거해도 `isCancelable()` 체크가 두 번째 cancel을 차단해 `canceledReason` 덮어쓰임이 안 일어남. 두 겹 방어의 가치 확인.
  - **Stage B pair 1·5에서 `canceledReason` 덮어쓰임 직접 캡처**. pair 1에서 *시스템은 새 reason으로 갱신됐는데 LLM은 \"이미 취소\"라고 거짓 응답* — 응답 표면 ↔ 시스템 실재 분리라는 가장 위험한 패턴을 데이터로 처음 봤음.
  - Outcome 4개의 자연어 친화성이 *AI에 확실한 답변을 유도*하는 데 결정적임을 baseline pair 4의 응답으로 입증.
- 막힌 점 / 해결:
  - QUEST는 `ALREADY_CANCELED` 분기만 제거해도 `canceledReason` 덮어쓰임이 일어날 거라 기대한 듯한데, 우리 코드 구조에선 NOT_CANCELABLE이 안전망으로 잡아줘서 단일 분기 제거로는 *진짜 망가짐*을 못 봤다. 둘 다 제거(Stage B)하기로 결정.
  - reset endpoint 도입이 Tool 호출률을 떨어뜨리는 부수효과 발견 (의문점으로 남겨둠).

### 3단계 — Tool description 정량 비교

- 기간: 2026-05-25
- 한 일:
  - `getDeliveryStatus`의 description을 3가지 버전(A 강의 자료 / B 빈약 한 줄 / C 오해 유발 한 줄)으로 바꿔가며 동일 발화 \"주문번호 2024-1234 배달 어디쯤이에요?\"를 10회씩 호출 (총 30 trial).
- 배운 점 / 개인 의견:
  - **description 짧아질수록 호출률 단조 감소** — 4요소 풀버전 10/10 → A(강의 자료 4줄) 5/10 → B(한 줄) 0/10 → C(오해) 0/10. 가장 명확한 정량 데이터.
  - **C(오해)가 B(빈약)와 동일 결과** — *틀린 광고든 빈약한 광고든 LLM 입장에서는 같은 \"쓸 수 없는 Tool\"*.
  - A의 NONE 응답 5건이 1단계 v1 scn 2의 \"주문번호를 알려주시겠어요?\" 모방 패턴과 *완전 동일* — *부족한 description의 실패 모드는 모두 같은 자리로 수렴*.
- 막힌 점 / 해결: 발화가 1단계와 살짝 다른 점(\"어디쯤에 있어요?\" vs \"어디쯤이에요?\")이 정확한 비교에 영향 줄 수 있음을 인지하고 데이터 옆에 명시.

### 4단계 — Observability + AI 코드 리뷰

- 기간: 2026-05-25
- 한 일:
  - `AssistantController`에 `SimpleLoggerAdvisor`를 *임시* 추가해 1차 LLM 호출 prompt 전문 캡처 후 제거.
  - `ChatController`에 `PerformanceLoggingAdvisor` 영구 추가 (Round 1에 누락된 부분, 토큰 비교 baseline 확보).
  - 3 시나리오 × 10 trial = 30 trial 토큰 측정 + Tool 호출 trial 2건 별도 캡처. `/api/v1/chat`(32 토큰) vs `/api/v1/assistant`(1217 토큰) vs Tool 호출 시(2586 토큰) 정량 비교.
  - AI 코드 리뷰 — \"Spring AI 1.0으로 배달 주문 취소 Tool을 만들어줘\"로 받은 코드에서 결함 3개((a) 로깅 없음, (c) 동시성 보호 없음 `@Transactional`만, (S1) NOT_FOUND vs ACCESS_DENIED enumeration)와 개선 코드 작성.
- 배운 점 / 개인 의견:
  - **Tool 등록만으로 +38배 토큰, 호출 시 +81배** — 정확도와 토큰 비용의 trade-off 곡선이 매우 가파름. 어떤 Tool을 등록할지·description을 얼마나 자세히 쓸지가 운영 의사결정의 핵심.
  - **2차 LLM round-trip은 `SimpleLoggerAdvisor`에 보이지 않지만 토큰 점프(1235 → 2586)로 입증** — 직접 관찰 못 하는 메커니즘도 *간접 신호*로 확인 가능.
  - **AI 코드 리뷰의 진짜 결함은 *운영급(로깅·동시성)*과 *보안(Enumeration)*에 있음** — Round 1에서 본 \"표면 결함보다 보안·접근통제가 진짜\"의 연장선. Round 1 피드백 \"AI 코드 리뷰가 피상적이었던 케이스 부분 점수 1위\"를 의식해 깊이 있는 분석으로 갔음.
- 막힌 점 / 해결:
  - 4단계 측정 환경에서 Tool 호출률이 1단계 v1 대비 매우 낮아짐(매 trial reset 영향 추정). 토큰 측정에는 호출 안 한 trial도 의미 있어 그대로 보존하고 의문점으로 남김.

## Round 2 전체 매듭

- 측정 데이터: 1단계 250 + 2단계 80 + 3단계 30 + 4단계 40 = **400 trial** 보존.
- 산출물: README.md (모든 단계 raw 데이터 + AI 코드 리뷰 통합), `EXPERIMENT_LOG{,_QUEST2,_QUEST3,_QUEST4}.md`, `DESIGN_DECISIONS.md`(Q1~Q10), `LEARNING_LOG.md`(1단계 + 2·3·4단계 통합 회고).
- 변경 파일 정확히 20개 — QUEST 한계 충족.
- 가장 큰 학습: *측정 사이클*과 *부정적 결과의 가치*. 5번 회귀한 가설 검증 흐름이 가장 강한 산출물.


> 사용자가 정제 후 정식 JOURNAL.md에 추가하는 draft.
> Round 2 entry 스타일 따름.

---

## Round 3 — Chat Memory + conversationId

- **기간**: 2026-05-28 ~ 2026-05-31 (진행 중, recovery 마무리 단계)
- **한 일** (4단계 + JDBC 워크트리):
  - **1단계** — Memory 3레이어 구현 (`InMemoryChatMemoryRepository` + `MessageWindowChatMemory(20)` + `MessageChatMemoryAdvisor(order=10)`), `SessionController` 3 endpoint, `X-Session-Id` 헤더 + `ChatMemory.CONVERSATION_ID` 주입. 시나리오 5종 × 20 trial = **100 trial** 측정 (21.9분). reset 인프라(`TestResetController` + `OrderMockService.resetForTest()`)도 Round 2에서 가져와 부활.
  - **2단계** — `MAX_MESSAGES` 3값(2/20/`Integer.MAX_VALUE`) × 10 repeat × 10 turn = **300 turn** 측정 (26.7분). `MessageWindowChatMemory` 바이트코드 분석으로 *0 이하 시 `IllegalArgumentException`*, *SystemMessage 무조건 유지*, *ToolMessage 미저장* invariant 확정.
  - **2단계 b** (장기 누적 효과) — 30턴 시퀀스 × 10 repeat × 3값. maxMAX 정상(10/10)이지만 max2(2/10)·max20(0/10) 손실 → 원인은 *내 `pkill -f BaedalSupportApplication`이 main bootRun까지 죽인 실수* + script fragility 결합. measure-quest2b.sh를 robust화(curl 실패 fallback, JSONL line 항상 보장)한 뒤 max2·max20만 재측정 (recovery 진행 중).
  - **3단계 (JDBC, 워크트리)** — `/private/tmp/loopers-quest3` 워크트리(port 8081)에서 `spring-ai-starter-model-chat-memory-repository-jdbc` + H2 의존성 추가, `ChatMemoryConfig.chatMemoryRepository`에 `@Profile("!jdbc")`. **`schema-h2.sql`을 직접 작성**해야 함을 발견 (Spring AI 1.0이 PostgreSQL·MySQL schema는 제공하지만 H2는 미포함). `initialize-schema: embedded`는 *in-memory만 적용*이라 *h2:file*은 `always` 필요. h2:mem 재시작 시 소실 / h2:file 재시작 후 유지 둘 다 검증.
  - **4단계** — Observability: 토큰 분해 종합 (baseline ~1100 + ToolMessage layer ~1500 + USER/ASSISTANT layer ~100-1000). 1단계의 *+1200 미스터리*를 *ChatClient 내부 tool calling history*로 완전 해결. AI 코드 리뷰는 **Workflow tool 3-agent pipeline** (naive 생성 → 결함 8개 분석 → 개선 코드 + lesson 매핑) 사용.

- **배운 점 / 개인 의견**:
  - **메모리 기반 vs DB 영속화는 운영 방식에 따라 정답이 다르다** — 세션별 식별 (InMemory) vs DB 영속화 (JdbcChatMemoryRepository) 중 어느 게 옳다고 단정할 수 없음. 단일 인스턴스 + 짧은 세션이면 InMemory가 합리, 멀티 인스턴스·재시작 보존·법적 감사가 필요하면 JDBC. *운영 조건이 선택을 강제*하지 *기술 자체*가 우열을 가르지 않음.
  - **`MAX_MESSAGES = 20`은 단순 예측치가 아니라 실제 검증값** — 너무 많은 것(MAX_VALUE)도 너무 적은 것(MAX=2)도 좋지 않고 *20개가 적당한 크기*. 2단계 10턴 측정 + 2단계 b 30턴 누적에서 *V자 sweet spot* (max2 4262 > max20 3704 < maxMAX 4055 토큰) + 옛 정보 회수 (T8: max2 4/10 vs max20 10/10) 둘 다로 확인.
  - **리서치로 다른 사람들과 비교 + 새로운 인사이트** — 우리 *지시 대명사 해결 70%* ("아까 물어본 그 주문")가 *production-ready*인지 외부 검증 필요. 산업 SLA 리서치 결과 *read ≥75% / write ≥90% + HITL*이 배달 도메인 권장 — 우리 70%는 *경계선* (산업 권장보다 약간 낮음). AI draft만으로는 *합리적 추정*에 그치고, 외부 자료가 *기준 자체*를 조정하는 데이터를 줌.

- **AI 추가 통찰** (EXPERIMENT_LOG·DESIGN_DECISIONS에 상세):
  - 가설 1·2 검증 — 가설 1 *조건부 검증* (78% 완화), 가설 2 *부분 부정* (Memory ≠ SOT)
  - **+1200 토큰 미스터리** = ToolMessage layer (Memory 외부) — 4단계 TRACE prompt 캡처로 직접 증명
  - **측정 인프라 사고** — `pkill -f` 광범위 매칭으로 main 측정 손실 → PID 명시 default + script robust 패턴 학습
  - **Workflow tool로 AI 코드 리뷰 자동화** — 3 agent sequential. 사람이 *프롬프트만* 작성하고 *결함 8개 + 개선 코드 + lesson 매핑*까지 자동. AI 발견 8 결함 중 CRITICAL 2개(세션 오염·멀티 인스턴스)가 1단계 보안 사고 시뮬레이션과 정확 일치

- **막힌 점 / 해결**:
  - **+1200 토큰 미스터리 (1단계 발견 5)** — SessionController는 USER/ASSISTANT 4개만 보여주는데 T2 입력 토큰 +1200. Spring AI library 바이트코드 분석 (Explore agent) + 2단계 MAX 3값 비교로 *ToolMessage layer (Memory 외부)*가 원인 확정. 학습 가치 큼.
  - **JDBC `Table not found` (3단계)** — Spring AI 1.0 starter가 H2 schema 미포함. `src/main/resources/org/springframework/ai/chat/memory/repository/jdbc/schema-h2.sql` 직접 작성으로 해결.
  - **`initialize-schema: embedded` ≠ h2:file** — h2:file은 *파일 영속*이라 *embedded 미분류*. `always`로 강제.
  - **`pkill -f BaedalSupportApplication`이 main bootRun까지 죽임** — main 측정의 max2 8건 손실 + max20 전체 손실 → recovery 필요. 교훈: PID 명시.

- **의문점**:
  - **턴을 더 늘리면 (50턴·100턴) 어떻게 될지** — 2단계 b는 30턴까지 측정. *50턴+에서 토큰 폭증* 예상이지만 *정확한 곡선·sweet spot 변화*는 미측정. 발제 4.6 *"100턴이면 18,000 토큰"* 추정도 *직접 검증* 안 됨.
  - **MAX_MESSAGES 1개씩 증가 (18, 17, 16 등)도 채택 가능했는지** — 측정은 *2 / 20 / MAX* 3값만. *18·17·16처럼 미세 변화*가 토큰·정확도에 *어떤 영향*인지 안 봄. 20이 정말 *최적*인지, 아니면 *18·22도 동등*한지 미확인.
  - **데이터 쌓이면 환각 ↑** — Workflow 리서치로 *Lost in the Middle (arxiv 2307.03172)* 및 *환각 인과 분석 (arxiv 2510.20229)* 확인. 그런데 *어떻게 해결*할지가 의문. 요약 전략으로 *완화*는 가능하지만 *근본 해결*은 없는 듯.
  - **Round 3 측정 자체에서 남은 의문** (기존):
    - reset 부수효과 #1 (Round 2 미해결) — Round 3에서 *방향성 일치* (-25%)이나 원인 미규명 (Ollama KV 캐시? HTTP 연결 풀? LLM 입력 신호?)
    - turn 7·17·27 "그거" 정확도 60% 천장 — 시퀀스 맥락 무게 vs 시간 우선 rule
    - 응답-실재 분리 다양한 형태 재발 — Round 5 Guardrail 또는 상태 cross-check Advisor

- **Round 4에 시도하고 싶은 것**:
  - **Memory + RAG 활성화로 데이터 기반 AI 답변** — Round 4 PgVector + QuestionAnswerAdvisor. 환불 정책·메뉴 정보 등 *도메인 지식*을 RAG로 끌어와 Round 3에서 *Tool 미호출로 못 풀던 응답-실재 분리*를 *지식 기반*으로 해결.
  - **여기서 생길 보안 취약점 테스트 + 막기** — RAG 도입 시 prompt injection (OWASP LLM01) 공격 표면 확장. Round 4 + 5단계 Guardrail에서 *prompt injection 시뮬레이션 + allowlist·output filter 방어* 직접 테스트. Round 3에서 CVE-2026-41712 시뮬레이션 + Workflow AI 코드 리뷰 8 결함 학습한 흐름을 *Round 4 RAG·벡터 검색 영역*으로 확장.

## Round 3 전체 매듭

- 측정 데이터: 1단계 100 trial (21.9분) + 2단계 300 turn (26.7분) + 2단계 b 30턴 누적 900 turn (recovery 포함 약 1.6시간) + 3단계 JDBC 영속성 검증 + 4단계 Observability + AI 코드 리뷰 (Workflow). 총 ~1,300+ turn.
- 산출물 (`round3/` 디렉토리 7개):
  - `README.md` — Round 3 entry point + 13 핵심 발견 + 가설 검증
  - `EXPERIMENT_LOG_QUEST1.md` — 1단계 8 발견 + 가설 검증
  - `EXPERIMENT_LOG_QUEST2.md` — 2단계 12 발견 + +1200 미스터리 해결 + 30턴 V자 패턴
  - `EXPERIMENT_LOG_QUEST3.md` — 3단계 영속성 + 의사결정 트리 + 4 함정 + H2 Shell SQL 캡처
  - `EXPERIMENT_LOG_QUEST4.md` — Observability + AI 코드 리뷰 + Memory prompt TRACE 캡처
  - `DESIGN_DECISIONS.md` — 1·2·3단계 10 질문 모두 답 (Workflow 6회 리서치로 검증)
  - `quest3-h2-console-sql.txt` + `quest4-trace-evidence.txt` + `quest4-prompt-payload.txt` — 자가 점검 raw 보존
- 워크트리 (`/private/tmp/loopers-quest3`, `round-3-jdbc` 브랜치 commit `46951e0`) — main(round-3)에 cp 완료. 워크트리 *추후 삭제 가능*.
- 변경 파일 수: 약 19개 (코드 11 + round3/ 7 + JOURNAL.md) — 20개 한도 충족.
- **가장 큰 학습 (사용자 답)**: *Memory 기반 vs DB 영속화의 정답은 운영 방식에 따라 다르고, MAX_MESSAGES = 20은 단순 예측이 아닌 실제 검증값이며, 리서치로 외부 기준과 비교해 새로운 인사이트를 얻는 흐름이 정량 측정 학습의 핵심.*
- **방법론적 학습**: *가설을 정량 측정으로 완전 검증 / 부분 검증 / 부분 부정으로 나눠 결론낸 흐름* + *AI draft 비판·수정 → 리서치 보강 → 본인 답으로 완성*의 3축 워크플로우.

## Round 3 매듭 후 정리 작업

- **PR #29 본문 서사 리메이크** — 측정 데이터 표·체크리스트 위주에서 *상황 → 선택 → 결과* 서사 흐름으로 update. 페어 리뷰어 가독성 ↑. 리뷰 가이드 3축(설계 결정 근거 / 실패 관찰 구체성 / 다음 라운드 연결) 그대로 유지.
- **외부 학습 5 패턴 정리** — 페어 학습 중 본 다른 PR의 추상·방식을 *Round 4·5 도입 검토 자산*으로 보존: Tool guard 정책-실행 분리 + 두 단계 confirm / `@Profile`-conditional storage zero-touch swap / Advisor order의 의미와 Observability 함정 / 의사결정 트리로 답 구조화 / 모든 추상 레이어에 JUnit 테스트.
- **워크트리 정리** — `/private/tmp/loopers-quest3` 제거. `round-3-jdbc` 브랜치는 보존 (히스토리 추적용).
- **PR #29 상태** — OPEN, 페어 리뷰 대기 중.

---

# Round 4 (RAG + PgVector) — 1단계 매듭

기간: 2026-06-04 ~ 2026-06-05 (1단계 정식 결정 완료)

## 1단계 완료 매트릭스

| 결정 | 정식값 | 측정 근거 |
|---|---|---|
| **TOP_K** | **4** | K sweep 240 ask, 역U자 발견, K=7=K=10 정량 증명 |
| **SIMILARITY_THRESHOLD** | **0.5** | T sweep 180 ask, 가설 부정 + 진단, 한국어 정답 score ~0.5 부근 |
| **Splitter** | **800/350 (임시 유지)** | 2단계 본격 청크 실험 예고 |
| **BaedalPrompt** | **임시 5섹션 baseline 유지** | 학술 강화 시도 부정 결과, *체크리스트 < 금지* 발견 |

## 측정 인프라

- bash + curl + jq + JSONL (Round 3 패턴 + macOS python3 ts 폴백 + flock 제거)
- 총 480+ ask 측정 (K sweep 240 + T sweep 180 + smoke v1 30 + smoke v2 30)
- 자동화 스크립트: `run-sweep.sh` (K) / `run-threshold-sweep.sh` (T) / `measure-smoke.sh` (BaedalPrompt v1·v2)
- raw 보존: `.private/notes/round4/` (16 파일)

## 핵심 발견 4가지

### 1. K-품질 곡선 *역U자* (직관 반박)

- K↑ ≠ 품질↑. K=4 정점.
- K=7에서 *Tool 분기 false routing* 발생 (정답률 8→5/10)
- K=7 = K=10 입력 토큰 동일 → vector_store 7 row가 K의 실효 상한 *정량 증명*
- K=10 scn 5a에서 한국어→중국어 코드 스위치 1건 (qwen2.5 long-context 한계 ~5,300 토큰)

### 2. THRESHOLD cliff drop (가설 부정 + 3축 진단)

- 가설: T=0.65로 noise 차단 → 정답률 ↑
- 실측: scn 1·2·3 *8→0 / 10→0 / 7→0* 일제히 cliff drop
- 3축 진단으로 *진짜 원인 = THRESHOLD 컷오프* high confidence 확정:
  - 입력 토큰 collapse (avg 청크 수 2.03 → 0.10)
  - 정책 키워드 소실 + 결정적 fallback (scn 3 10/10)
  - 환경 노이즈 6가지 모두 clean (재기동·시드·HTTP·trial 순서·outlier 무관)
- **한국어 + 짧은 정책 FAQ 정답 score가 ~0.5 부근에 분포** — 외부 Qwen3 일반론 (0.60~0.76) *반박*

### 3. *체크리스트 < 금지* (BaedalPrompt 학술 강화 부정 결과)

- 7 학술 패턴 + 4 산업 패턴 통합 시도 (Constitutional / TRUST-ALIGN / Quote-then-Summarize / FActScore / Lost-in-Middle / ReAct / CoT + LangChain / Anthropic / DoorDash / KT ds)
- Smoke v1: Markdown 헤더 인용·자가점검 폐기·scn 3 0/10 Fallback 폭증
- Smoke v2: placeholder 누출 (*"정책 원문 한 줄"*)·섹션 라벨 누출·Markdown 7건·Fallback 9건
- 진단: qwen2.5 7B의 *복잡한 메타 룰 instruction following 한계* + 한국어 표현 격차
- **사용자 통찰** — *"체크리스트는 없어도 되고 금지가 더 나은가?"* 측정 데이터로 확정:
  - 짧은 단정문 negative imperative: scn 4 **40/40** 견고 ✅
  - 명령형 + 고정 문구 Fallback: scn 3 T=0.65 **10/10** 결정적 ✅
  - 체크리스트·CoT: ❌ 작동 안 함
- 학습 자산 3가지:
  1. 짧고 결정적 negative imperative만 system prompt
  2. 체크리스트·CoT는 모델 검증 후 도입
  3. 학술 best practice는 모델·언어별 측정 검증 필수

### 4. 측정 인프라 사고·교훈

- **macOS flock 부재** — 첫 sweep에서 모든 K 0 lines 사고. PAR=1 직렬에선 atomic write 불필요 → flock 제거 + 학습 자산화
- **bootRun 종료 PID 명시 원칙 유지** — Round 3 *pkill 사고* 교훈 이월 (`kill $(lsof -ti tcp:8080)` 형태로 정착)

## 가설 검증 결과

| 가설 (Round 3 회고 이월 포함) | 결과 |
|---|---|
| Memory + RAG로 데이터 기반 답변 (scn 5b 협업) | ✅ 9~10/10 일관 (K 변동 비의존) |
| RAG 도입 시 prompt injection 시뮬레이션 + 방어 (scn 4) | ✅ privacy 거절 40/40 견고 |
| 학술 강화 (Constitutional + Quote-then-Summarize 등)로 정답률 ↑ | ❌ **부정** — qwen2.5 7B 한계로 역효과 (25/30 → ~1/30) |
| 더 큰 K (7·10) = 더 좋은 품질 | ❌ **부정** — 역U자 발견 |
| Higher THRESHOLD (0.65/0.75) = noise 차단으로 품질 ↑ | ❌ **부정** — 정답 청크 컷됨 |
| 체크리스트형 룰이 grounded refusal에 효과적 (FActScore 발상) | ❌ **부정** — *체크리스트 < 금지* (qwen2.5에서) |

## 산출물 (1단계)

- `round4/EXPERIMENT_LOG_QUEST1.md` (423줄):
  - 1-A K sweep (240 ask + 6 발견 + 의사결정 트리)
  - 1-B T sweep (180 ask + 가설 부정 + 3축 진단)
  - 1-C BaedalPrompt 학술 강화 부정 결과 (smoke v1·v2 + 체크리스트 < 금지)
- `.private/notes/round4/` 16 파일 — raw JSONL + bootrun 로그 + 측정 인프라 스크립트 + 학술 강화 draft 자산

## 본인 회고 (1단계 — *학습자 직접 작성*)

### 내가 배운 것

> *(여기서부터 본인이 채울 부분. Round 3 패턴 따라.)*
> 예시 후보:
> - Workflow tool로 학술·산업 리서치 + 측정·분석을 *parallel*로 돌리는 경험
> - *학술 best practice가 우리 모델/언어에서 작동 안 함*을 직접 측정으로 확인하는 학습 가치
> - *체크리스트 < 금지* 통찰 — qwen2.5 같은 작은 모델에는 단정문 negative imperative가 효과적
> - 측정 인프라 사고 (flock 부재)·해결 과정

### 의문점

> *(학습자 작성)*
> 예시 후보:
> - T를 더 낮추면 (0.4·0.3) noise 부작용이 정말 발생할지 — 미측정
> - 학술 강화가 *어떤 모델 크기 임계*에서부터 작동하는지 — Round 5에서 큰 모델로 검증?
> - Constitutional negative imperative를 룰 1·3·5에 *확장 적용*하면 baseline보다 더 좋아질지 — 미측정

### 2단계 (Splitter 청크 실험) + Round 5에 시도하고 싶은 것

> *(학습자 작성)*
> 예시 후보:
> - 2단계: chunkSize 200·800·2000 비교 — scn 1·3 partial citation을 *청크 분할로 풀 수 있는지* 검증
> - Round 5 Guardrail: 더 큰 모델 (qwen3·llama3.1 70B 등)로 학술 강화 재시도
> - Round 5: prompt injection 시뮬레이션 본격화 + Output filter 추가

---

## Round 4 — 2단계 매듭

기간: 2026-06-05 ~ 2026-06-06

### 2단계 결정 매트릭스

| 결정 | 정식값 | 측정 근거 |
|---|---|---|
| **`TokenTextSplitter.chunkSize`** | **800 / minChunkSizeChars 350** (임시값 → 정식 승격) | 4 phase × 50 ask = 200 ask, 의사결정 트리 5단계, blur·fragmentation 직접 캡처 |

### 측정 인프라 (2단계)

- bash + curl + jq + JSONL (Round 3·1단계 패턴 유지)
- 자동화: `run-splitter-sweep.sh` (sed RagConfig + **python3 BaedalPrompt** + bootRun 재기동 × 4 + 측정 × 4) + `measure-quest2.sh`
- 총 200 ask (chunk-800 50 + α 50 + chunk-100 50 + chunk-2000 50)
- raw: `.private/notes/round4/quest2-splitter-sweep.jsonl` (150) + `quest2-no-policy-rule.jsonl` (50) + bootrun-{4 phases}.log

### 핵심 발견 6가지 (2단계)

#### 1. **chunk-2000 ≈ chunk-800 — Blur 확인**
FAQ 25-35줄(~300~600 토큰)이라 800·2000 둘 다 1 FAQ = 1 chunk. 정답률·응답 표현 거의 일치. 청크 키우기 ROI 0.

#### 2. **chunk-100 문맥 조각남 캡처 (가설 ✅)**
- 청크 수 폭증 (7 → **49**)
- scn 1: *"60분 이상"*만 retrieve, 11~29·30~59 구간 누락
- scn 4: 한국어→중국어 코드 스위치 + *"음식물량"* 합성어 hallucination (grounding 부족 + 토큰 흔들림)

#### 3. **Partial citation은 Splitter 문제 *아님***
scn 1·4 partial 인용이 *어떤 chunkSize에서도 해결 안 됨*. LLM·룰 차원 문제 — *측정으로 확정*.

#### 4. 🎯 **α 룰 ROI 3-분리 (Round 4 최대 학습 자산)**

| 시나리오 유형 | 룰 ROI | 측정 |
|---|---|---|
| **도메인 가드 (scn 5)** | **∞** | 10/10 → **0/10** hallu 5 (Fallback 완전 소실) |
| **Context grounding 강제력 (scn 2)** | **8x** | 8/10 → **1/10** silent failure (예상 외 발견) |
| **FAQ 인용 (scn 1·3·4)** | 1x | 5~30% 차이만 |

→ 1단계 *"체크리스트 < 금지"* 통찰 강화. **금지 룰의 *3-역할 동시 수행*: 도메인 가드 + Context grounding + 부가 인용**. (ii) Context grounding 강제력은 *기존 학술/산업 가이드에 명시 안 된 효과* — 우리 측정 자산.

#### 5. **Silent Failure 패턴 발견 (α)**
LLM이 *"거짓말로 채우기"*보다 *"안전한 정보 요청 반복"*으로 수렴. *명백한 hallucination보다 검출 어려움* — Round 5 Guardrail 의제.

#### 6. **Latency 절감 lever — system prompt > splitter**
- chunk-100 splitter: **-17%** (단 fragmentation)
- **no-policy-rule system prompt slim화: -21%** (단 룰 ROI 잃음)
- → Trade-off 신중. 함부로 룰 빼면 silent failure 폭증.

### 2단계 가설 검증 결과

| 가설 | 결과 |
|---|---|
| chunk-100 = 구간 분할로 partial citation 해결 | ❌ **부정** (fragmentation으로 악화) |
| chunk-2000 = 유사도 뭉툭·토큰 낭비 | ⚠️ 부분 (blur + max token 폭증) |
| α = 룰 제거 시 환각·범위 밖 응답 증가 | ✅ **확정** + 예상 외 **silent failure 패턴** 발견 |
| α = 룰이 *Context grounding* 강제 | ✅ **확정** (학술·산업 가이드 미명시) |
| 1단계 *"체크리스트 < 금지"* 추가 검증 | ✅ **강화** + 3-역할 분리 |

### 측정 인프라 사고·복구 (학습 자산)

- **v1 사고**: α 단계 awk 패턴 실패 → BaedalPrompt 본문 망가뜨림 → bootRun fail
- **v2 안전화**: awk → **python3** (`find` + 슬라이스, 결정적) + 컴파일 사전 검증 + FATAL handler 보강
- 학습: *BSD awk + 들여쓰기 있는 Java text block* 부적합 → *python3 또는 파일 swap* 권장

### 산출물 (2단계)

- `round4/EXPERIMENT_LOG_QUEST2.md` (신규) — 측정 설계·결과·핵심 발견·정식 결정
- `.private/notes/round4/decisions-log-quest2.md` (판단 기록) — 옵션·번복·실패 시도·통찰 흐름
- `.private/notes/round4/quest2-splitter-sweep.jsonl` 150 + `quest2-no-policy-rule.jsonl` 50
- bootrun-{chunk-800·100·2000·no-policy-rule}.log
- `measure-quest2.sh` + `run-splitter-sweep.sh` (python3 안전화)

### 미해결·이월 (3단계 + Round 5)

- partial citation (scn 1·4) — Splitter로 풀 수 없음. Memory 또는 룰 시도 (단 학술 강화 부정 결과 위험)
- long-tail latency (chunk-800 max 29초) — streaming/cache layer 분리
- Silent failure 패턴 — Round 5 Guardrail 의제
- chunk-100 scn 2 우수 (10/10) — 시나리오별 맞춤 청크 가능성? Round 5 의제

---

## Round 4 — 3단계 매듭

기간: 2026-06-06

### 3단계 결정 매트릭스

| 결정 | 정식값 | 측정 근거 |
|---|---|---|
| **Advisor order** | **Memory(10) → RAG(20) → Performance(100)** (정상 유지) | 2 phase × 10 trial × 2 turn = 40 ask + RestClient body raw payload 직접 캡처 |

### 측정 인프라 (3단계)

- *교란 관찰* 흐름 — 1·2단계 *최적값 sweep*과 다른 패턴 (사용자 통찰: *"이미 설정된 값에서 조금씩 바꾸는 식"*)
- `run-advisor-order-sweep.sh` — sed RagConfig.order() + python3 application.yml RestClient DEBUG 임시 + trap 자동 복원
- `measure-quest3.sh` — 2턴 대화 × 10 반복 × 2 phase
- raw: `quest3-advisor-order.jsonl` 40 lines + bootrun-quest3-{order-20-normal·order-5-broken}.log (648KB, 68 ChatRequest payload)

### 핵심 발견 6가지 (3단계)

#### 1. 🚨 **Memory 오염 (예상 외 발견)**
QUEST 힌트는 *"뒤바꿈에서 RAG가 무관 정책 retrieve"* 예상. 실측은 다름 — *"환불"* 키워드 매칭 충분, **두 phase 모두 refund 정상 retrieve**.

**진짜 결함**: RAG가 *Memory 복원 전*에 USER 메시지를 *Context 보일러플레이트로 변형* → *변형본이 Memory에 영구 저장* → 다음 턴 USER가 오염 (RestClient body raw에서 직접 캡처).

#### 2. **Advisor 체인 = 프롬프트 슬롯 경쟁** (미들웨어 스택 아님)
order *낮을수록* 바깥쪽 envelope, *높을수록* user 메시지 옆.

#### 3. **Memory entity 복원은 order에 *독립적***
1234 복원: 정상 10/10 = 뒤바꿈 10/10. **개별 advisor 성능과 advisor 협업 효과 분리해서 봐야**.

#### 4. **정책 인용·Fallback은 순서에 민감**
refund 7→5/10, fallback 5→3/10. SafeGuard·RAG가 user에서 멀어지면 *"when in doubt, escalate"* 약화.

#### 5. **Latency cost는 *멀티턴 누적 시점*에서 표면화**
턴 1 뒤바꿈 *2초 빠름*, 턴 2 *+17% 역전*. *단일 턴 아닌 누적 시점에 표면화*.

#### 6. **조용한 결함** — HTTP 0 errors. raw payload 캡처가 *탐지의 유일한 수단*

### 외부 학습 5 패턴 #3 한 단계 진화

| 함정 | 위치 | 결과 |
|---|---|---|
| (A) 관찰자 함정 (이전 PR — 외부 학습) | SimpleLogger(0) < Memory(10) | *Memory 변형 전 prompt 로깅* |
| **(B) 생산자-소비자 함정** (본 실험) | RAG(5) < Memory(10) | *Memory 변형 전 input으로 retrieve + 오염 저장* |

→ **추상 한 단계 진화**: *"Advisor 체인 설계는 dataflow 그래프 설계이지 미들웨어 스택 설계가 아니다."*

### 3단계 가설 검증 결과

| 가설 | 결과 |
|---|---|
| 정상 순서에서 2턴 통합 응답 | ✅ refund 7/10 · 1234 10/10 |
| 뒤바꿈에서 RAG가 무관 정책 retrieve | ❌ **예상 부정** (*"환불"* 키워드로 정상 retrieve) |
| Advisor order 의미 직접 증명 | ✅ + **Memory 오염 발견** (예상 외) |
| 외부 학습 5 패턴 #3 검증 | ✅ + **두 함정 통합 추상 강화** |

### 산출물 (3단계)

- `round4/EXPERIMENT_LOG_QUEST3.md` (신규) — QUEST 본문 관찰 표 + 설계 결정 답 + Memory 오염 정제본
- `.private/notes/round4/decisions-log-quest3.md` (판단 기록) — 예상 vs 실측 + 외부 학습 5 패턴 #3 진화 흐름
- `quest3-advisor-order.jsonl` 40 lines + bootrun-{2 phases}.log 648KB
- `measure-quest3.sh` + `run-advisor-order-sweep.sh`

### 미해결·이월 (4단계 + Round 5)

- **Memory 오염의 누적 효과** — 본 실험은 2턴만, 10턴+ 누적 시 토큰·품질 더 악화될지 미측정
- **reverseOrderUseCases 재현** — PII 마스킹·prompt injection 시뮬레이션 Round 5 Guardrail 의제
- **Round 4 4단계** — Observability + AI 코드 리뷰 진입 (본 실험의 *조용한 결함은 raw payload 캡처가 필수* 발견이 정확한 동기)

---

## Round 4 — 4단계 매듭 (Observability + AI 코드 리뷰)

### 측정 (Observability — 9 ask)

3 phase × 3 trial — 단일 질문 *"배달 완료 후에도 환불 받을 수 있나요?"*
- (a) `performanceAdvisor` 만
- (b) +Memory
- (c) +Memory+RAG (baseline)

### RAG 비용의 정량 정체 — *P5 컨텍스트 인플레이션*

| 지표 | (a) → (c) | 백분율 |
|---|---|---|
| **입력 토큰** | 1524 → **2426** | **+902 (+59.2%)** |
| **출력 토큰** | 48.7 → 102.7 | +110.9% (~2배) |
| **응답 시간** (cold 제외) | 2903 → 5394ms | +85.8% |
| **응답 길이** | 75자 → 160자 | ~2배 |

→ RAG = *정책 청크 N건을 USER 메시지 안에 통째로 박는 비용을 지불하고, 출력에서 근거 있는 인용을 얻는다*.

### Context 블록 raw — *컨텍스트 인플레이션의 물리적 증거*

`bootrun-quest4-phase-c.log`에서 USER content 1673자 raw 캡처. 정책 청크 2건(배달 완료 후 환불 정책 + 환불 기본 정책)이 USER 메시지 안에 통째 박힘.

특기: 정책은 **USER 슬롯**에 박힘 (SYSTEM 슬롯은 BaedalPrompt 그대로 보존) → *시스템 프롬프트 prefix cache 친화적* 구조.

### 핵심 발견 5가지

#### 1. **RAG = 입력 +59.2% / 출력 ~2배 / 응답 ~2배** (P5 정량화)
컨텍스트 인플레이션은 추상이 아닌 *+902 token / +2491ms*의 정량 비용.

#### 2. **Memory cold cost ≈ 0** (a) = (b) 입력 1524 token 동일
trial별 새 sid라 Memory가 *cold 상태*. *누적 비용*은 별 측정 필요 (3단계 Memory 오염과 연결).

#### 3. **컨텍스트는 USER 슬롯에 박힌다** (RestClient raw로 확인)
QuestionAnswerAdvisor 표준은 *USER 메시지 끝 prepend*. SYSTEM 슬롯 불변 → prefix cache 보존.

#### 4. **출력 길이도 RAG 따라 ~2배** (49 → 103 token)
(a)·(b) 응답은 일반 안내, (c) 응답은 4가지 환불 사유 직접 인용. *Context를 받은 LLM은 근거를 더 길게 답함*.

#### 5. **5 패턴은 우리 회고가 아니라 외부 평가 룰셋**
본 AI 코드 리뷰에서 *Gemini 코드 5/5 패턴 위반*을 일관되게 탐지 — 학습 자산이 *임의의 RAG 코드 진단 룰*로 작동함.

### AI 코드 리뷰 (Gemini 3.5 flash 답안 결함 분석)

5 렌즈 병렬 분석 (Workflow) → 18 raw findings → **Top 3 + 잔여 7건**.

| Rank | 결함 | 패턴 매핑 |
|:-:|---|---|
| 🥇 1 | **Advisor dataflow 그래프 자체 미형성** — `builder.build()` + `vectorStore.similaritySearch` 직접 호출 + Memory 미연결 | P3 직격 |
| 🥈 2 | **도메인 가드 ∞ ROI + 결정적 fallback 부재** — systemPrompt 2문장 | P1·P2 통합 |
| 🥉 3 | **PerformanceLoggingAdvisor + similarityThreshold 부재** — 인플레이션·재시드·환각 비가시 | P4·P5 통합 |

### Round 4 발견 5 패턴 위반 분포 (Gemini 코드)

| 패턴 | 위반 |
|---|:-:|
| P1 체크리스트 < 금지 | ❌ |
| P2 룰 ROI 3-분리 | ❌ |
| P3 Advisor = dataflow 그래프 | ❌ |
| P4 조용한 결함 = raw payload 캡처 | ❌ |
| P5 RAG = 컨텍스트 인플레이션 | ❌ |

→ **5/5 위반**. Top 3 fix만 적용해도 잔여 7건 중 5건 자동 흡수.

### 한 줄 진단

> **Gemini는 RAG 컴포넌트를 *기능 단위*로 호출하지만 *Advisor 체인 = dataflow 그래프* 설계 자체를 포기했다.**
> 그 결과 P1~P5 다섯 패턴이 동시에 위반되고 모든 결함이 운영 메트릭으로 비가시화된다.

### 4단계 가설 검증 결과

| 가설 | 결과 |
|---|---|
| RAG로 입력 토큰 의미 있게 증가 | ✅ +902 token (+59.2%) |
| Memory cold cost ≈ 0 | ✅ (a) = (b) 1524 |
| Context는 USER 슬롯에 박힘 | ✅ raw 확인 — prefix cache 보존 |
| 출력 토큰도 함께 증가 | ✅ ~2배 (49 → 103) |
| Gemini AI 코드는 5 패턴 다수 위반 | ✅ **5/5 위반** — 가설 강하게 부합 |

### 산출물 (4단계)

- `round4/EXPERIMENT_LOG_QUEST4.md` (신규) — Observability 정량 + AI 코드 리뷰 통합 정제본
- `.private/notes/round4/quest4-ai-code-review-draft.md` (신규) — Gemini 코드 결함 분석 Top 3 + 잔여 7건 + Round 4 발견 5 패턴 매핑
- `.private/notes/round4/decisions-log-quest4.md` (판단 기록) — 측정 설계 흐름·5 렌즈 다관점 분석 결정 흐름
- `quest4-token-comparison.jsonl` 9 lines + `bootrun-quest4-phase-{a,b,c}.log` ~106KB
- `measure-quest4.sh` + `run-token-cost-sweep.sh`

### 정식 결정 — *5 패턴 모두 baseline 유지*

본 4단계는 *측정·검증*이지 *변경*이 아니다.

### 미해결·이월 (Round 5)

- **Memory 누적 비용** — cold만, 같은 sid 10턴+ 누적 시 비용 추이 미측정
- **임베딩 호출 비용** — PerformanceLoggingAdvisor는 LLM 호출만 캡처
- **응답 품질 sentinel** — context 인용 boolean + fallback 발동 boolean
- **Gemini fix 적용 후 회귀 측정** — Top 3 fix 적용 → 동일 sweep → 정량 개선
- **재시드 idempotency 측정** — `KnowledgeLoader.alreadyLoaded` 효과 정량

---

## Round 4 — 라운드 마무리 회고 (4단계 통합)

### 본 라운드 학습 자산 5 패턴 (P1~P5)

| 패턴 | 단계 | 정량 근거 |
|---|---|---|
| **P1** 체크리스트 < 금지 | 1·2단계 | 강화 프롬프트 negate → baseline 5섹션 정식화 |
| **P2** 룰 ROI 3-분리 | 2단계 | 도메인 가드 ∞ / Context grounding 8x / FAQ 인용 1x |
| **P3** Advisor = dataflow 그래프 | 3단계 | Memory 오염 raw payload (`bootrun-quest3-order-5-broken.log` 372KB) |
| **P4** 조용한 결함 = raw payload | 3·4단계 | 두 단계 모두 RestClient DEBUG가 결정타 |
| **P5** 컨텍스트 인플레이션 | 4단계 | +902 token / +2491ms / ~2배 출력 |

### 자산의 외부화

본 라운드 4단계 AI 코드 리뷰에서 5 패턴이 *임의의 RAG 코드 진단 룰*로 작동함을 검증.
→ Round 5 Guardrail에서 *5 패턴 + 추가 안전 룰*을 *외부 평가 룰셋*으로 정식화 의제.

### Round 5 진입 의제

1. **Guardrail 패턴** — PII 마스킹·prompt injection 차단 (3단계 reverseOrderUseCases 재현)
2. **Memory 누적 비용 + sentinel** — 4단계 cold 측정의 확장
3. **임베딩 호출 advisor** — 4단계 LLM-only PerformanceLoggingAdvisor의 확장
4. **응답 품질 sentinel** — context 인용 / fallback 발동 boolean
5. **5 패턴 외부 평가 룰셋 정식화** — 4단계 AI 코드 리뷰 학습 자산을 *재사용 가능한 도구*로

---

# Round 5 (안전장치 Guardrail) — 라운드 매듭

기간: 2026-06-14 ~ 2026-06-16

## 4단계 한 일

- **1단계 Input Guardrail** — `check()`(빈입력 `isBlank` / 길이 2000 / injection 정규식) + `order=5`로 체인 맨 앞. 차단을 LLM 전 short-circuit으로 처리해 비용 0. 구조 버그 2건 수정(`ChatClient.Builder` 싱글톤 누적 *Multiple tools* / 빈입력 `.user("")` 거부 → 컨트롤러 선검사).
- **2단계 Output Guardrail + 마스킹** — `maskPhone/Email/Address` + `LEAK_MARKERS`, `order=50`. 원본→마스킹 라이브 캡처(`010-1234-5678` → `010-****-5678`).
- **3단계 Handoff** — `EXPLICIT → LEGAL → ANGER` 우선순위, LLM 호출 *전* 선검사. `/api/v1/support`는 `SupportResponse` 수동 조립(ETC/HIGH).
- **4단계 Fallback + AI 코드 리뷰** — `try/catch` fallback(스택트레이스 비노출 + 연결번호), 실패 3경로 검증, AI 생성 코드 결함 3개.

## 측정 데이터

- 1단계 50 trial + 2차 우회 34개, 2단계 30 trial + 우회 25개, 3단계 20 trial + 우회 23개 + 라이브 캡처(support 스키마·원본↔마스킹).
- raw: `.private/notes/round5/` (quest1~4 jsonl + findings + 분석 스크립트 + `findings_live_capture.md`)

## 핵심 발견

1. **short-circuit / handoff 비용 0** — 차단을 LLM 앞에 두니 **2195배·704배** 빠르고 토큰 0. order 위치 하나가 비용·메모리 오염을 좌우.
2. **패턴/마커 방어의 본질적 한계** — 정규식·마커는 형식·언어·의미 변형 우회를 못 막음(Input **91%**·Handoff **100%** 우회). 정규화는 코드포인트만 줄이고(55%↓) 의미·다국어는 못 막음. 보완하면 새 빈틈(국제표기→한글숫자, `[역할]`→`역할:`→영어 `Role:`).
3. **NFKC 자모 역설** — `ㅅ ㅂ` 호환자모(U+3145)를 정규화하면 조합용 자모(U+1109)로 바뀌어 오히려 패턴 매칭이 깨짐. 정규화 도입 시 패턴도 같은 폼이어야.
4. 🚨 **Tool Calling + Structured Output 충돌** — `/api/v1/support` 정상 요청이 Tool 호출 시 **HTTP 500**. qwen2.5가 Tool 실행 후 JSON format 무시 → 자연어 → `BeanOutputConverter` 파싱 실패. `entity(SupportResponse.class)`+Tool 구조적 문제로, 이전 라운드부터 잠재했으나 그 조합 미테스트로 미발견 → 체크리스트 점검 중 라이브 캡처로 처음 발견.
5. **Spring AI Tool 예외 처리** — Tool throw를 `DefaultToolExecutionExceptionProcessor`가 가로채 LLM에 전달 → fallback은 *chat 호출 자체 실패*에서만 발동(과제 "Tool→fallback" 가정과 다름).

## AI 코드 리뷰 결함 3개

① 출력 마스킹 order(+200) < Memory(+1000) → 평문 PII 메모리 저장 ② 입력 정규화 부재 코드포인트 우회 ③ 출력단 시스템 프롬프트 유출 탐지 부재.

## 산출물

- `round5/README.md` — 4단계 측정·설계결정(10자리) + AI 코드 리뷰 + 학습기록 (placeholder 0)
- 코드: `guardrail/` 6개 + 테스트 3개 + `AssistantController`·`SupportController` + `build.gradle`
- 변경 24건(순수 5주차 작업 **13** + 5주차 starter 유입 11 — `knowledge-extra` 8 + `rag` 2 + BaedalPrompt)
- **PR #56 OPEN** (round-5 → loopers-labs:APapeIsName) — 선택 심화(+5점, LLM 분류기) **미구현 명시**(필요성은 우회 측정으로 입증, 구현은 Round 6 이후)

## 본인 회고 (1인칭)

### 내가 배운 것

다층 방어가 여러 안전사고를 방지하는 데 도움을 주는 것은 사실이나, 직접 우회를 시도해보니 생각보다 뚫리는 길이 많았다(Input 정규식 91%, Handoff 규칙 100% 우회). 이런 우회로들을 차단하는 방법을 고안해야 실제 제품 환경으로 이어갈 수 있을 것 같다. 그리고 이건 단순 모델 하나로 해결할 수 없을 것 같고, 에이전트 오케스트레이터나 여러 스킬·프롬프트 등의 노력으로 에이전트 자체를 강화하는 쪽이 더 낫다고 느꼈다.

### 의문점

우회로를 감지하려고 분류 LLM이나 에이전트 오케스트레이터를 도입한다면 그 비용을 감당할 수 있을까? 우리 측정에서 LLM 호출 전에 차단한 경우(short-circuit·handoff)는 비용이 0이었지만, 분류 LLM을 매 요청 앞단에 세우면 호출이 한 번 더 붙어 비용·지연이 늘어난다. 그리고 그 분류 LLM마저 통과하는 민감정보·우회 입력이 나오면 그때는 어떻게 막나? 결국 이 모든 우회를 전부 막는 게 가능하기는 한 걸까?

### Round 6에 시도하고 싶은 것

이제는 모니터링을 붙여보고 싶다. 가드레일이 얼마나 많이 차단(실패 응답)을 냈는지, 어떤 우회·차단 질문들이 들어왔는지를 상시 지표로 보고 싶다. 이번 라운드에선 우회율을 수동으로 측정했지만, 실제 운영이라면 Micrometer 같은 메트릭으로 "어떤 패턴이 얼마나 뚫리고 막히는지"를 계속 관측해야 할 것 같다.

## Round 6 진입 의제

1. **모니터링(Micrometer)** — 차단·우회·실패 응답을 상시 지표로 (본인 회고 직접 연결)
2. **분류 LLM 의심 트래픽 선별 투입** — 선택 심화(+5점) 이월, 모니터링으로 *무엇을 보낼지* 데이터 확보 후
3. **Handoff 시 대화 요약 전달** — 상담원이 맥락을 처음부터 다시 안 묻도록
4. **Tool + Structured Output 500 근본 수정** — Tool 응답 후 JSON 변환 2-pass 분리
5. **NFKC 자모 역설 해소** — 입력·패턴 정규화 폼 일치
