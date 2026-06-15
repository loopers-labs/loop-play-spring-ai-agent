Round 4 전체 코드 리뷰 종합
Round 4
+ 2
준비중
Round 4 (RAG) 전체 코드 리뷰 종합
작성일: 2026-06-08 / 근거: 실제 제출된 OPEN PR 9건(#42~#50)을 week4/code-review-guide.md 기준으로 head SHA 직접 리뷰
성격: 강사용 회고·통계 문서. 학생 공개용이 아니라, Round 5 운영 판단과 다음 기수 가이드 보강에 쓰는 자료.
한눈에 보기
PR
작성자
완료 단계
체크리스트(9)
비고
#42
옹재성
1·3 + 2일부
8 / 1​
[정책 인용 규칙] 5번째 축(범위 밖) 미명시
#43
신형기
1~4 + 공통
9​
JSON 구조화 응답으로 Fallback 변주(confidenceLevel)
#44
공명선
4단계
9​
650+ ask sweep로 권장값 정당화
#45
홍세영
4단계
9​
orphaned Config bean 1건
#46
배정은
4단계
9​
청크 실험 TRUNCATE 수행(row 800→7/100→49/2000→6)
#47
소윤범
4단계(AI리뷰 제외)
9​
ablation으로 실패 원인 위치까지 특정
#48
김세은
4단계
9 / 1​
RagProperties 외부화 + 검색근거 자동 로깅 advisor
#49
최준아
3단계
9​
order 통설을 코드 동작으로 교정
#50
김평숙
4단계
9​
dead bean 1건, 자가점검 1줄 미체크
요약 통계
- 9건 중 8건이 체크리스트 9개 항목 전부 충족. 흔한 실수 10종에 명확히 걸린 제출은 사실상 0건.
- /는 단 2건(#42 프롬프트 5축 1개 누락, #48 청크 TRUNCATE 코드상 확인 불가).
- 평가 범위 미달(#42 2단계 일부 / #49 3단계 / #47 AI리뷰 제외)은 감점이 아니라 다음 단계 안내로 처리.
  총평: Round 4는 “RAG 기계적 연결”을 넘어 검색 품질 경계(청크/Top-K/임계값/Fallback)의 설계 근거를 본인 언어로 증명하는 것이 목표였는데, 이번 기수는 그 목표를 매우 높은 수준으로 달성했다. 코드 정확성은 평준화됐고, 변별은 관찰·판단의 깊이에서 갈렸다.
  잘된 점 — 이번 기수가 특히 강했던 지점
1. 흔한 실수 10종을 거의 전원이 회피
   dimensions=1024 ↔︎ qwen3-embedding:0.6b 일치, order(20) 명시, topK + similarityThreshold 동시 설정, alreadyLoaded의 ==+similarityThresholdAll(), ApplicationRunner, metadata 3종(faqId/title/category), 두 Controller 모두 ragAdvisor — 이 핵심 7개가 거의 전 제출에서 일관되게 충족됐다. 가이드와 모범답안이 잘 흡수됐다는 신호.
2. “통설을 코드 동작으로 교정”한 발견 (복수)
   여러 제출자(#42, #49)가 “Memory가 복원한 질문을 RAG가 임베딩한다”는 교과서적 설명을, 실제로는 현재 턴 텍스트만 임베딩된다는 코드 동작으로 반박했다. order(20) 스왑이 byte-identical 임베딩을 낳는다는 것까지 짚은 제출도 있었다. 가이드가 지향하는 “관찰로 통설을 검증” 태도의 모범 사례.
3. 실측·ablation 기반 설계 정당화
   #44: 650+ ask sweep로 K=4 / T=0.5 / chunk-800을 본인 데이터로 정당화
   #46: 청크 실험에서 TRUNCATE 후 재적재로 row 수 변화(800→7 / 100→49 / 2000→6) 실증
   #47: S5 실패(coref+정책 0/5), 청크 C≡A, num_ctx 4096 천장을 통제 ablation으로 원인 위치까지 특정
   #48: RagProperties로 RAG 설정 외부화(baedal.rag.*) → 재컴파일 없이 실험, RagRetrievalLoggingAdvisor(order 30)로 검색 근거 자동 로깅
   반복적으로 짚게 된 패턴 (감점 아님 — 다음 라운드/기수 보강 포인트)
   이번엔 “흔한 실수 10종”보다 그 너머의 옅은 패턴이 반복됐다. Round 5/6 통합 때 부채가 될 수 있어 기록한다.
   패턴 A. Orphaned / dead bean — AssistantChatClientConfig
   #45, #50에서 동일하게 관찰. ragAdvisor가 빠진(3주차 상태) ChatClient Bean이 Config에 남아 있는데, 컨트롤러는 이 Bean을 안 쓰고 생성자에서 자체적으로 전체 체인을 빌드한다. 동작은 정상이지만 “RAG가 안 붙은 것처럼 보이는” Config가 코드에 남아 다음 리뷰어·6주차 통합 시 혼란을 준다.
   → 6주차 통합 강의에서 “ChatClient 조립을 한 곳(SSOT)으로 모으기”를 명시적으로 다루면 좋다.
   패턴 B. 차원 변경 시 테이블 DROP 운영 절차 누락
   #44, #47, #48에서 관찰. dimensions 값 자체는 1024로 정확하지만, initialize-schema: true가 기존 테이블을 재생성하지 않는다는 점(= 모델 차원 바꾸면 DROP 후 재기동 필요)이 README 운영 메모에 빠져 있다. 코드 결함은 아니나, 다음 기수가 nomic→qwen3 전환을 겪을 때 “조용한 실패”로 되돌아올 위험.
   → 가이드 “흔한 실수 1”에 이미 있으나, 운영 절차(DROP)를 체크리스트 항목으로 승격하면 더 안전.
   패턴 C. alreadyLoaded의 더미 query("정책") + faqId 기반 한계
   #45, #48, #49, #50에서 관찰. 중복 방지 로직 자체는 정확(==+similarityThresholdAll)하지만, (1) 더미 query가 placeholder임을 주석으로 안 남겨 다음 리뷰어가 의아해하고, (2) faqId 키 기반이라 문서 본문이 바뀌어도 갱신 안 됨(스킵 처리). 교육용으론 충분하나 실무 한계.
   → 가이드 모범답안에 “더미 query 주석” 한 줄과 “본문 변경 미감지 한계 → 해시 기반 upsert” 각주를 추가 권장.
   데이터로 드러난 가이드 보강 제안
   방금 리뷰로 가이드(week4/code-review-guide.md) 자체에 추가하면 좋을 것:
   체크리스트 D(심화)에 “ChatClient 조립 SSOT” 항목 추가 — 패턴 A가 2건이나 나왔다. “ragAdvisor가 빠진 미사용 ChatClient Config가 남아 있지 않은가”를 명시.
   “흔한 실수 1(dimensions)”에 운영 절차 박스 추가 — 값 일치만이 아니라 “차원 변경 시 DROP 후 재기동” 절차를 별도 강조. 패턴 B가 3건.
   모범답안 alreadyLoaded에 주석/각주 보강 — 패턴 C가 4건. 더미 query 의도 + 본문 변경 미감지 한계.
   Round 5 운영에 시사하는 것
   코드 정확성이 평준화됐으므로, Round 5(Guardrail)에서도 “붙이기”는 쉽게 통과할 것이다. 변별은 Round 4와 똑같이 경계 설계(FP/FN, 과잉 마스킹, 규칙 기반 한계)의 관찰 깊이에서 갈린다 — 미션 설계 의도와 정확히 맞물린다.
   Round 4에서 “통설을 코드로 교정”한 학생들은 Round 5에서 “정규식이 정상 입력을 차단하는 경계”를 같은 방식으로 파고들 가능성이 높다. 이 강점을 라이브에서 호명·강화하면 좋다.
   패턴 A(dead bean)는 Round 5·6에서 Advisor가 더 늘어나면 더 커진다. 6주차 통합 전에 한 번 정리를 권하는 코멘트를 남겨두는 것이 좋다.
   게시된 리뷰 코멘트 링크
   PR
   URL
   #42
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/42#issuecomment-4647676763
   #43
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/43#issuecomment-4647674835
   #44
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/44#issuecomment-4647676280
   #45
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/45#issuecomment-4647674350
   #46
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/46#issuecomment-4647679085
   #47
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/47#issuecomment-4647692767
   #48
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/48#issuecomment-4647691516
   #49
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/49#issuecomment-4647686341
   #50
   https://github.com/loopers-labs/loop-play-spring-ai-agent/pull/50#issuecomment-4647691641
