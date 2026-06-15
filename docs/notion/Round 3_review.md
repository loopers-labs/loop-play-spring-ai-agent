Round 3 피드백
Round 3
+ 1
준비중
Round 3 코드 리뷰 종합 피드백 — 대화 맥락 관리와 메모리 설계
🎯 대상: Round 3(3주차 · ChatMemory) PR 8건 — [Round 3]로 시작하는 OPEN PR
기준: week3/code-review-guide.md(체크리스트 / 흔한 실수 9 / 모범 답안)
검증 방식: 각 PR의 소스 diff를 코드 레벨로 정적 리뷰(빌드/실행 아님). PR 본문의 자가 보고는 코드로 교차검증.
이 문서는 8개 PR을 가로질러 본 패턴 정리입니다. 개별 PR별 상세 리뷰는 같은 폴더의 PR-NN_이름.md를 참고하세요.
한 줄 메시지
이번 라운드의 진짜 평가축은 “Memory를 켰는가”가 아니라 그 기억의 경계 — 크기(MAX_MESSAGES) / 저장소(InMemory↔︎JDBC) / 세션(X-Session-Id) — 를 근거를 갖고 설계했는가였습니다. 8건 모두 “동작”은 했고, 차이는 전부 경계의 근거와 일관성에서 갈렸습니다.
PR별 한눈 요약
PR
작성자
신고 단계
코드 검증 결과
가장 큰 강점
가장 큰 개선점
#29
공명선
4단계
1~4 전부 + 모범답안 초과
2회차 prompt raw 캡처 / AutoConfig exclude 이중 방어
SupportController 누적 버그 / 세션ID 검증 미반영
#28
양권모
4단계
1~4 전부 + ADR 6건
schema-h2 미동봉 규명 / Memory≠감사로그 통찰
세션ID 검증 / 마스킹 식별-적용 간극
#27
소윤범
4단계
1~4 (Memory는 assistant만)
autoconfigure.exclude / 프롬프트 전문 DEBUG 덤프
X-Session-Id 검증 / Memory 스코핑 명시
#30
홍세영
4단계
1~4 전부
MAX_VALUE 토큰 역설 정직한 해석
문서-코드 불일치(default) / schema override
#31
홍성혁
3단계
1~3 전부
schema-h2 직접 작성 / 정량 실험
SupportController 누적 버그(blocker)
#36
최준아
3단계
1~3 (Round2 캐리오버 혼재)
Tool 재호출 누락 stale 드리프트 실측
[대화 맥락 사용 규칙] 누락 / yml 주석 코드와 불일치
#26
김세은
3단계
1~3 + Tool 가드
customerId:sessionId 결합 키 / initialize-schema:never
BaedalPrompt diff 미포함(맥락 규칙 확인 불가)
#33
김평숙
1단계
1단계 (품질 우수)
1단계를 모범답안 수준으로 / 실패 정직 기록
SupportController 누적 버그 / @Profile 미적용
💡 미완료 단계(예: #33의 2~4단계)는 감점이 아니라 ‘미완료’로 표기했습니다. 3주차 범위 밖(RAG/Guardrail 정식 필터링)도 감점하지 않되, 선제 고려가 있으면 칭찬으로 인정했습니다.
Round 3에서 가장 자주 짚게 된 패턴 (빈도순)
1위 — SupportController의 ChatClient.Builder 누적 버그 (4건+)
거의 모두에게 같은 코멘트를 달았습니다. AssistantController는 조립된 ChatClient 빈을 주입받아 빌더 누적을 피했는데, SupportController는 요청마다 builder.defaultTools(...).defaultAdvisors(...).build()를 호출해 같은 함정(Multiple tools with the same name)을 그대로 재현합니다. (#31은 blocker, #33·#29 major)
특히 인상적인 건, 여러 PR이 AssistantChatClientConfig Javadoc에 이 함정을 정확히 경고해놓고 바로 옆 SupportController에서 그 패턴을 밟았다는 점입니다. → 한쪽을 고쳤으면 같은 클래스의 형제 컨트롤러도 grep으로 한 번에 점검하는 습관이 필요합니다.
2위 — X-Session-Id 검증 부재: “분석은 깊은데 코드에 안 닿음” (거의 전원)
README에서는 default 폴백 위험 / IDOR / 세션 하이재킹 / 쿠키·JWT·URL·헤더 비교를 CVE·OWASP까지 매핑하며 깊이 분석해놓고(특히 #29·#36·#31·#33), 정작 컨트롤러 코드는 @RequestHeader(defaultValue="default")로 받은 값을 길이/패턴 검증 없이 그대로 CONVERSATION_ID로 넘깁니다.
폴백을 막는 것(required=true)과 받은 값을 신뢰하지 않는 것(^[A-Za-z0-9-]{8,64}$ 검증)은 별개의 방어선입니다. 3주차 범위로는 README 인식만으로 충분하지만, “분석→코드 반영”의 고리를 한 줄로 잇는 것이 다음 라운드 숙제입니다.
3위 — [대화 맥락 사용 규칙] System Prompt 섹션의 운명 (3건)
#36 최준아: [Tool 사용 규칙]만 추가되고 필수인 [대화 맥락 사용 규칙] 섹션이 코드에 없음. 시나리오가 3/3 통과한 건 Tool 규칙 + 모델 능력에 기댄 측면.
#27 소윤범: 추가했다가 ablation으로 “Tool 호출률 80%→20% 억제”를 발견해 의도적으로 영구 제거. 이건 단순 누락과 질이 다른, 데이터로 의사결정을 뒤집은 사례 — “모델 탓이 아니라 prompt가 통제 변수였다”는 결론은 이 과정이 기르려는 판단력 그 자체입니다.
#26 김세은: BaedalPrompt.java가 PR diff에 없어 섹션 존재를 코드로 확인 불가.
4위 — JDBC 전환의 Spring AI 1.0 GA 함정 (여러 명이 독립적으로 발견)
이번 라운드의 숨은 학습 포인트. Spring AI 1.0.0 JDBC starter가 H2 스키마(schema-h2.sql)를 패키징하지 않아 bootRun이 실패합니다. 학생들이 각자 다른 방식으로 해결했습니다:
#28 양권모: platform: postgresql로 schema-postgresql.sql을 강제 로드
#29 공명선·#31 홍성혁·#36 최준아: 정확한 classpath 경로에 schema-h2.sql을 직접 작성
#30 홍세영: 직접 작성하되 라이브러리 번들과 동일 경로를 덮어쓰는(shadowing) 방식 → 버전 업 시 silent 불일치 위험을 별도로 지적
또한 initialize-schema: embedded가 h2:file을 임베디드로 판정하지 않아 테이블이 안 생기는 함정도 다수가 만나 always로 우회했습니다.
⚠️ 강사 메모(자료 갱신 포인트): week3/code-review-guide.md 모범 답안의 application-jdbc.yml 예시(initialize-schema: embedded)는 GA에서 h2:file과 함께 쓰면 깨집니다. 학생들이 공통으로 부딪힌 만큼, 가이드/강의 노트에 “1.0.0 GA는 schema-h2.sql 미동봉 + embedded는 file DB 미초기화 → always 또는 직접 스키마 제공”을 명시하는 게 좋겠습니다.
5위 — 개인정보 마스킹: 인식만, 코드 미구현 (전원, 감점 아님)
전원이 README/문서에서 “Memory 영속화 = 개인정보 처리자가 됨”을 인식했으나, 실제 정규식 마스킹 코드는 아무도 시도하지 않았습니다. 3주차는 ’시도까지’만 요구하므로 감점 대상이 아닙니다. 다만 #28처럼 AI 코드 리뷰에서 “프라이버시 무필터”를 결함으로 식별한 학생은, 정작 본인 입력 경로에는 적용하지 않은 “식별-적용 간극”이 있어 한 줄 시도를 권했습니다.
이번 라운드의 빛난 지점 (구체적 칭찬)
#26 김세은 — 세션 키를 X-Session-Id 단독이 아니라 customerId:sessionId로 결합해 세션 탈취·테넌트 오염을 구조적으로 차단. /ids도 현재 고객 것만 노출. 가이드 “실수 1”을 코드 설계로 봉쇄한 유일한 케이스.
#29 공명선 — 2회차 Memory 주입 prompt를 raw payload로 직접 캡처(quest4-prompt-payload.txt). “Memory가 SYSTEM 앞에 prepend된다”는 동작을 눈으로 증명. + application.yml AutoConfiguration exclude로 JDBC Bean 충돌 이중 방어.
#30 홍세영 — MAX_VALUE인데 평균 입력 토큰이 maxMessages(20)보다 낮게 나온 역설을 숨기지 않고 “Tool JSON 하나가 텍스트 메시지 수십 개보다 토큰을 더 먹는다”로 정확히 해석. 정량 실험의 정직함.
#36 최준아 — 메모리 재활용 시 Tool 재호출이 빠지며 stale 데이터를 재서술(도착 연도 2026→2024 드리프트)하는 상호작용을 통제 실험으로 포착. 가이드 “흔한 실수 9”를 학생이 스스로 발견.
#28 양권모 — MessageWindowChatMemory가 saveAllreplace + 비트랜잭셔널이라 감사 로그가 될 수 없다(ADR-005)는 점까지 파고듦. 3주차 범위를 넘는 운영 통찰.
#27 소윤범 — PerformanceLoggingAdvisor에 프롬프트 전문 DEBUG 덤프를 넣어 Memory 주입을 가시화. 3주차 핵심을 눈으로 증명하는 도구.
다음 라운드(Round 4 · RAG)로의 다리
이번 라운드에서 다수가 MessageChatMemoryAdvisor.order(10)을 명시하며 “4주차에 RAG Advisor가 Memory(10)와 Performance(100) 사이(order=20)에 끼어든다”는 선제 설계를 코드 주석/README에 남겼습니다. 이건 Round 4의 핵심 그림입니다:
memory(10) → rag(20) → performance(100)   // 낮을수록 먼저
​
순서가 중요한 이유: RAG가 “그거 환불되나요?”의 “그거”를 검색 질의로 쓰려면 Memory가 먼저 지시 대명사를 복원해야 합니다. 이번 라운드에서 order를 근거 있게 배치하고 TOOL 메시지 미저장→RAG 역할 분리까지 통찰한 PR(#31·#26·#27)은 Round 4 진입 토대가 이미 마련되어 있습니다.
