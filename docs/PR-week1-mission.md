# PR: Round 1 — Spring AI 배달 상담 에이전트

## Round 1 — 완료한 단계

- [x] 1단계: 기본 API + System Prompt + Structured Output
- [x] 2단계: Prompt Engineering 정량 비교 + 실패 관찰
- [x] 3단계: SSE 스트리밍 응답
- [x] 4단계: Observability — PerformanceLoggingAdvisor

막힌 곳 없음. 4단계 모두 완료.

---

## 핵심 설계 결정 3가지

**1. `actionability` enum 4값 추가**

`nextAction` 자유 텍스트만으로는 "즉시 처리 가능"과 "담당팀 확인 필요"를 구조적으로 구별할 수 없었다. 4값(IMMEDIATE / NEEDS_INFO / NEEDS_REVIEW / ESCALATED)으로 분리했다.

- 2값(IMMEDIATE/ESCALATED)이었다면: "주문번호가 없어서 확인이 필요한" 케이스를 ESCALATED에 넣어야 해 프론트에서 상담원 연결 UI가 과도하게 뜸
- 6값 이상이었다면: qwen2.5 같은 소형 모델에서 경계 케이스 분류 일관성이 떨어짐. 실험에서 5회 반복 시 이미 COMPLAINT/DELIVERY 사이에서 흔들리는 것 확인

**2. `[금지]` 규칙 4가지 선택**

타사 추천·개인정보·쿠폰 약속은 법적 리스크가 직접적이다. 의료 판단 금지는 배달 음식 알레르기·식중독 케이스에서 현실적으로 필요해 추가했다.

- 타사 추천 없이: "쿠팡이츠가 더 빠르지 않냐"에 AI가 묵시적으로 동의 → 공식 채널이 경쟁사 우위를 인정한 것처럼 SNS에 확산
- 의료 금지 없이: "음식 먹고 배가 아픈데 괜찮냐"에 "경미한 증상 같으니 물 마세요" 응답 → 실제 응급 상황에서 의료기관 방문 지연

**3. temperature 0.3 선택**

0.0 / 0.3 / 0.7을 직접 실험하지 않았지만, 5회 반복 실험에서 0.3에서 시나리오 2개는 5/5 일관성, 1개는 3/5를 얻었다.

- 0.0이면: 동일 입력에 동일 출력 → 봇처럼 느껴지고 유사 질문에 copy-paste 응답
- 0.7이면: 시나리오 3에서 이미 흔들리는 0.3보다 일관성이 더 낮아질 것으로 예상

---

## 가장 흥미로웠던 실패 관찰

`[금지]` 섹션을 제거하고 "사장님 전화번호 알려줘"를 보냈을 때:

| | summary |
|--|---------|
| **[금지] 있음** | "사장님 전화번호 공개는 **금지되어 있습니다**. 직접적인 의사소통은 배달원에게 위임해야 합니다." |
| **[금지] 없음** | "현재 배달 앱에서는 배달사장님의 전화번호 공개가 **불가능합니다**." |

표면적으로 둘 다 거절했다. 그런데 거절의 근거가 달랐다. [금지] 있을 때는 정책 원칙("금지")으로 거절하고, 없을 때는 기술적 제약("앱에서 불가능")으로 거절했다. temperature가 올라가거나 "앱 밖으로 연락 방법은?"처럼 우회 질문이 오면 두 번째 응답이 "직접 연락을 시도해보세요"로 변질될 수 있다.

경쟁사 비교 시나리오에서는 더 명확했다. "쿠팡이츠가 더 나은 것 같은데 어떻게 생각해?"에 [금지] 없을 때:

> "해당 의견은 내부에서 검토하여 **개선 방안을 모색하겠습니다.**"

비교 질문의 전제(쿠팡이츠가 더 낫다)를 묵시적으로 인정하면서 답했다. 공식 AI 상담 채널이 경쟁사 우위를 인정한 것처럼 해석될 수 있는 응답이다.

---

## 리뷰 요청 포인트

- `PromptLabController`의 반복 호출 구조 — `for` 루프로 단순 구현했는데, 병렬 호출이나 비동기로 바꾸는 게 의미 있는지
- 2단계 실험에서 시나리오 3(라이더 음식 엎음)만 구조화 프롬프트 consistency가 0.60으로 낮았는데, 이게 프롬프트 구조 문제인지 시나리오 자체가 경계 케이스라서인지 확신이 없음

---

## 변경 파일 목록 (20개)

| 파일 | 변경 내용 |
|------|---------|
| `SupportResponse.java` | `estimatedResolutionMinutes`, `Actionability` enum, `COMPLAINT` category 추가 |
| `BaedalPrompt.java` | System Prompt 4섹션 설계 ([역할]/[규칙]/[금지]/[actionability 분류 기준]) |
| `SupportController.java` | ChatClient 빌더 체인 + PerformanceLoggingAdvisor 등록 |
| `PromptLabController.java` | 반복 호출로 consistency 측정 |
| `StreamingChatController.java` | SSE `Flux<String>` 스트리밍 |
| `PerformanceLoggingAdvisor.java` | elapsed time + token usage 로깅 |
| `build.gradle` | `useJUnitPlatform()` 추가 |
| `application.yml` | logging 설정 |
| `README.md` | 4단계 실험 결과 및 설계 결정 전체 기록 |
| `.gitignore` | `.claude/` 추가 |
| 테스트 6개 | 19개 테스트 전부 통과 |

---

## 셀프 리뷰 — 위험 신호 7가지 체크

| 위험 신호 | 결과 |
|---------|------|
| 코드만 있고 README가 없다 | ✅ README에 4단계 전체 기록 |
| 설계 결정이 "AI가 그렇게 하라고 해서요" | ✅ 양 옆 선택지에서 무엇이 깨지는지 기록 |
| 실패 관찰이 "안 됐어요" 한 줄 | ✅ LLM 응답 텍스트 직접 인용 |
| 체크리스트만 체크되고 본문 비어 있음 | ✅ 본문 있음 |
| 다음 라운드 아이디어가 "열심히 하겠습니다" | ✅ 조건부 취소/환불, 히스토리, Tool 안전 설계 구체적으로 기록 |
| 시나리오 응답이 강의자료와 토씨 하나 안 틀리고 같음 | ✅ 실제 Ollama 실행 결과 |
| `./gradlew build` 실패 | ✅ BUILD SUCCESSFUL |

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)