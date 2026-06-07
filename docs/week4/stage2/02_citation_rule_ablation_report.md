# [정책 인용 규칙] ablation 리포트 — similarityThreshold만으로 환각을 막을 수 있는가?

## 1. 실험 내용

- **무엇을**: `assistant_system_prompt.md`의 `[정책 인용 규칙]` 섹션(L67–91, `# 정책 인용 규칙` ~ `## 복수 정책 우선순위`)을 **임시 삭제**한 상태(ablated)와 **git 복원**한 상태(restored)에서, 동일한 도메인 밖 질문을 1회씩 보내 응답을 대조한다.
- **왜**: 환각 방지의 두 가드 — `similarityThreshold`(검색 단계, RagConfig `0.5`)와 `[정책 인용 규칙]`(프롬프트 단계) — 가 **각각 무엇을 막는지** 분리해 본다.
- **어떻게**:
  - 질문: `"오늘 점심 뭐 먹을까요?"` (도메인 밖) — `POST /api/v1/assistant`, session `s-q5`.
  - 엔드포인트: `AssistantController`(`BaedalPrompt.ASSISTANT_SYSTEM_PROMPT` → `assistant_system_prompt.md`). Advisor 체인 = Memory(10) → **QuestionAnswerAdvisor(20, RAG)** → Performance(100).
  - 실행기: `docs/verification_memory/run_memory_scenarios.sh` (실행마다 서버 새로 기동/종료 → ChatMemory 초기화, 단일 질문만 전송).
  - 절차 상세: [00_step_guide.md](citation_rule_ablation/00_step_guide.md). cases: [cases.json](citation_rule_ablation/cases.json).
- **제어 변수**: 두 런 모두 같은 질문·같은 모델·같은 threshold. **유일하게 다른 것은 `[정책 인용 규칙]` 섹션의 유무**다.

## 2. 결과

### 2-1. 검색 단계 — 두 런 모두 Context가 비었다

- 도메인 밖 질문은 어떤 정책 문서와도 유사도가 낮아 `threshold=0.5`에서 전부 탈락 → RAG가 주입한 Context 블록이 **빈 채로** user 메시지에 붙었다. (ablated/restored **동일**)

| 런 | user 메시지에 주입된 Context 블록 (server.log) |
|---|---|
| ablated | `---------------------`⏎`---------------------` (사이 **공백**) |
| restored | `---------------------`⏎`---------------------` (사이 **공백**) |

- 즉 검색 단계 가드(`threshold`)는 **두 경우 모두 똑같이 작동**했다. 무관 정책은 한 건도 주입되지 않았다.

### 2-2. 생성 단계 — 규칙 유무가 응답을 갈랐다

| 축 | ablated (규칙 제거) | restored (규칙 복원) |
|---|---|---|
| 응답 본문 | "죄송합니다, 제가 도와드릴 수 있는 **주문 번호가 필요해요**. **오늘 점심으로 어떤 음식을 주문하셨는지** 알려주실 수 있으신가요?" | "**주문·배달·취소·환불 관련 문의를 도와드리고 있습니다.** 점심 메뉴 추천은 제공하지 못합니다. 다른 질문이 있으시다면 도움 드리겠습니다." |
| 동작(범위 처리) | ❌ 상담 범위 이탈 — 점심 질문을 **주문 건으로 오인**, 주문번호를 되물음 (없는 맥락을 지어냄) | ✅ 범위 밖으로 인지 → 상담 가능 범위 안내 + 메뉴 추천 거절 |
| 응답 시간 | 58.4s | 22.1s |

- 산출물: [ablated_step5.json](citation_rule_ablation/responses/ablated_step5.json) · [restored_step5.json](citation_rule_ablation/responses/restored_step5.json) (각 raw 응답), `responses/*_server.log` (전체 wire 로그).

**핵심 관찰**: Context가 비어 있다는 사실(검색 가드의 결과)은 두 런이 똑같다. 그런데 규칙을 빼자 LLM은 QA 템플릿의 `"if the answer is not in the context, ... can't answer"` 지시만으로는 도메인을 지키지 못하고, 질문을 **주문 시나리오로 재구성**해 상담 범위를 벗어났다. 규칙을 복원하자 곧바로 범위 안내로 수렴했다.

- **참고**: Context가 0건이어도 위 영문 지시문(`"if the answer is not in the context, ... can't answer"`)은 검색 결과가 아니라 `QuestionAnswerAdvisor`의 고정 템플릿이라 항상 주입된다(`{question_answer_context}` 자리만 비고 나머지는 정적) — 즉 이 지시문이 상존함에도 규칙 없이는 범위 이탈을 못 막았다.

## 3. 답: "similarityThreshold만으로 환각을 막을 수 있는가?"

**아니오. 필요하지만 충분하지 않다.**

- 두 가드는 막는 대상이 **다르다.**

| 가드 | 위치 | 막는 것 | 못 막는 것 |
|---|---|---|---|
| `similarityThreshold=0.5` | 검색 단계 (`RagConfig`) | 무관 정책 문서가 Context에 **섞여 들어가는 것** | 주입할 게 없을 때 LLM이 **스스로 범위를 벗어나는 것** |
| `[정책 인용 규칙]` | 프롬프트 단계 (system) | 주입된 Context를 어떻게 쓸지 + **범위 밖 질문 거절** | 임베딩 부정확(경계값 근처) — 검색 단계의 몫 |

- 이번 ablation이 그 한계를 직접 보여준다: threshold가 **할 일을 완벽히 해서 Context를 비웠는데도**(무관 문서 0건 주입), 프롬프트 가드가 없으니 LLM은 점심 질문을 주문 건으로 오인하는 **범위 이탈**을 일으켰다.
  - threshold가 막는 환각: "무관 정책을 근거라고 끌어다 쓰는 환각" — 도메인 밖 질문에 정책 문서가 0건 주입됐으니 이 유형은 발생하지 않았다.
  - threshold가 못 막는 환각: "근거가 없을 때 LLM이 스스로 만들어내는 범위 밖 응답" — ablated에서 그대로 발생했다.
- 따라서 **검색 가드(threshold)와 생성 가드(`[정책 인용 규칙]`)는 협력해야** 환각 방지가 완성된다. threshold는 "무엇을 넣을지"를, 프롬프트 규칙은 "넣을 게 없거나 범위 밖일 때 어떻게 답할지"를 책임진다.

## 4. threshold가 "성공해도" 부족한 이유 — 두 논리의 구분

"`similarityThreshold`만으로 부족하다"는 결론에는 서로 다른 두 논리가 섞여 있다. 구분해 두면 이번 실험이 정확히 무엇을 증명했는지 분명해진다.

| 논리 | 부족한 이유 | 성격 |
|---|---|---|
| (A) threshold가 **제 일을 못할 때** | 경계가 빡빡해 의미상 관련 있는 문서도 탈락(false negative)하거나, 너무 낮으면 무관 문서가 섞인다 | 검색 정확도의 한계 |
| (B) threshold가 **제 일을 완벽히 해도** | 무관 문서를 다 걸러 Context가 비어도, LLM의 "생성"은 별개 영역이라 범위를 벗어난다 | 검색·생성 책임의 분리 |

- **이번 ablation이 보인 것은 (B)다.** 도메인 밖 질문이라 threshold는 정답 0건으로 **올바르게** 비웠다 — 잘못 거른 게 아니라, 애초에 들어갈 정책이 없는 질문이다. 그 이상적 조건에서도 규칙이 없으니 LLM은 점심 질문을 주문 건으로 오인했다.
- 따라서 이 실험은 *"threshold가 실패해서 부족"*이 아니라 *"threshold의 일(무엇을 넣을지)과 프롬프트의 일(없을 때·범위 밖일 때 어떻게 답할지)이 애초에 다른 영역이라서 부족"*을 보여준다.
- (A)는 threshold를 더 잘 튜닝하면 줄일 수 있는 빈틈이지만, (B)는 **threshold를 아무리 잘 잡아도 남는 빈틈**이다 — 검색 단계에서는 메울 수 없고, 프롬프트 가드(`[정책 인용 규칙]`)로만 메운다. 이번 실험은 검색 실패라는 교란 변수가 없어 (B)를 깔끔하게 분리해 보여준 사례다.

## 5. 한계 / 비고

- 단일 질문 1회 실행 — Ollama는 비결정적이라 ablated 응답의 **표현**(메뉴 추천 vs 주문번호 되묻기)은 실행마다 달라질 수 있다. 다만 "규칙이 없으면 범위를 벗어난다"는 **범주**는 재현될 것으로 본다.
- 검색 단계가 비결정적이지 않음은 확인됨(두 런 모두 Context 빈 채 주입 동일).
- 실험으로 변경한 소스(`assistant_system_prompt.md`)는 git으로 복원 완료.
