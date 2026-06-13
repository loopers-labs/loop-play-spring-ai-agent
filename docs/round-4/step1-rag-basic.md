# Round 4 · 1단계 — RAG 기본 + 정책/FAQ 검색

> 흐름: `knowledge/*.md`(정책 7건) → 임베딩(qwen3-embedding:0.6b, 1024dim) → PgVector(`vector_store`) → 질문 시 `QuestionAnswerAdvisor`가 Top-K 검색 후 프롬프트에 Context 주입.
> 엔드포인트 `POST /api/v1/assistant`. PgVector는 docker-compose(호스트 5433 — 로컬 native postgres 5432 충돌 회피).

## 적재 결과
```
[KnowledgeLoader] 적재 완료 — privacy / cancel-policy / coupon-faq / delay-compensation /
                              weather-delay / refund-after-delivered / refund-basic (각 청크 1개)
[KnowledgeLoader] RAG 시드 완료 — 신규 7건 / 스킵 0건 / 총 7건
vector_store row 수 = 7  (chunkSize 800에서 문서당 1청크)
```
재기동 시 `alreadyLoaded()`가 faqId로 중복 검사 → 스킵 7건(중복 적재 안 됨) 확인.

## 검증 — RAG 동작 / Fallback

| # | 질문 | 결과 |
|---|------|------|
| 1 | "음식 상해서 환불받고 싶어요. 환불 정책은?" | `refund-after-delivered` 인용: "품질 불량 사유 환불 가능, 사진 증빙 + 1:1 문의" ✅ 검색 |
| 2 | "배달이 너무 늦었는데 보상받을 수 있나요?" | `delay-compensation` 인용, **원문 수치 그대로**: "60분 이상 지연 → 전액 환불 검토" ✅ 검색 |
| 3 | "주문 취소는 언제까지 가능해요?" | "CREATED/ACCEPTED 취소 가능, COOKING 이후 불가" ✅ 검색 |
| 4 | "오늘 점심 뭐 먹을지 추천해줘" (범위 밖) | "배달 주문 관련 문의만 도와드릴 수 있습니다" — [정책 인용 규칙] Fallback ✅ |
| 5 | "상담할 때 카드번호나 비밀번호도 알려줘야 하나요?" | `account__privacy` 인용: "절대 요청하지 않습니다. 주문번호/연락처 뒷 4자리만" ✅ 검색 |

→ RAG 없이 물었으면 환불/보상 수치를 **지어냈을** 질문들인데, 검색된 정책 원문을 근거로 답함. 4번은 Context가 비어(임계값 미달) Fallback 문구로 안전하게 빠짐.

### 실패 관찰 — 작은 임베딩 모델의 검색 누락
in-domain 질문인데도 표현에 따라 Fallback으로 빠지는 경우 관찰:
- "쿠폰이 적용이 안 되는데 왜 그런 거예요?" → `coupon-faq` 문서가 있는데도 유사도가 임계값 0.5 밑 → Fallback("상담원 연결").
- "환불은 언제까지 신청할 수 있고 조건이 어떻게 되나요?" → 동일.
→ 정답 문서가 코퍼스에 있어도 **작은 임베딩 모델(qwen3-embedding:0.6b)이 질문↔문서 유사도를 낮게 매겨** 놓치는 것. threshold를 낮추면(0.3) 잡히지만 범위 밖 질문에 무관 정책이 끼는 부작용. → "임베딩 모델 크기 ↔ threshold"의 트레이드오프(3단계/리뷰 포인트와 연결).

## 부수 관찰 — qwen2.5 불안정성
범위 밖 질문(4번)에서 Fallback 문구 직후 "음식 종류 추천해드릴 수 있다"고 살짝 새거나, 일부 응답이 중국어/포맷 템플릿을 누출. 검색·주입 파이프라인은 정상이고, 변동은 생성 모델(qwen2.5) 레이어.

## 중복 적재 방지 증명 (재시작)
TRUNCATE 없이 앱을 재시작하면:
```
[KnowledgeLoader] RAG 시드 완료 — 신규 0건 / 스킵 7건 / 총 7건
```
→ `alreadyLoaded(faqId)`가 `filterExpression("faqId == '...'")` + `similarityThresholdAll()`로 기존 적재를 감지 → 재적재 안 함.

## Context 주입 증거 (검색된 정책이 프롬프트에 들어갔다는 증명)
Spring AI 1.0의 `QuestionAnswerAdvisor`는 주입 Context를 기본 DEBUG 로그로 출력하지 않는다. 대신 **응답의 원문 수치가 출처 문서와 일치하는지**로 주입을 증명한다(모델이 지어낼 수 없는 값).

질문: "비 오는 날 배달이 늦으면 보상 받을 수 있나요?"
응답(발췌): *"+30~59분 → 배달비 전액 환불 또는 **3,000원 쿠폰** 중 선택 / +60분 이상 → 전액 환불 / **기상 특보** 발효 시 제외 / 예상 시간 변경 없이 60분 이상이면 예외 보상"*

출처 `delivery-delay__delay-compensation.md` + `delivery-delay__weather-delay.md` 원문:
> | +30~59분 | **배달비 전액 환불** 또는 **3,000원 쿠폰** 중 선택 | … 기상 특보 … 제외 … 예상 시간이 변경되지 않은 채 60분 이상 지연된 경우 예외

→ 수치·조건이 **글자 그대로 일치** = 두 정책 청크가 검색되어 프롬프트에 주입됨. RAG 없으면 불가능.

## 실패 관찰 — [정책 인용 규칙] 제거 시 경계 붕괴
`BaedalPrompt`에서 `[정책 인용 규칙]`을 통째로 제거하고 범위 밖 질문 재현:

질문: "오늘 점심 뭐 먹을지 추천해줘"
- **규칙 있을 때**: "배달 주문 관련 문의만 도와드릴 수 있습니다" (Fallback)
- **규칙 제거 시**:
```
오늘 점심 메뉴 추천해드릴게요. ...
1. 피자  2. 샌드위치  3. 비빔밥 ...
```
→ 범위 밖인데도 **태연히 메뉴를 추천**(가이드가 경고한 "비빔밥" 환각). **범위 밖 차단은 similarityThreshold(0.5)가 아니라 [정책 인용 규칙]이 책임진다**는 증거. 임계값은 무관 문서를 거를 뿐 "답하지 마라"를 시키지 못한다 → **임계값(검색) + 프롬프트 규칙(생성) 2중 방어** 필요. (복원 후 다시 Fallback 확인.)

## 설계 결정 (1단계) — *초안, 제출 전 검토*
- **TOP_K=4**: 정책 문서가 7건뿐이라 4면 관련 정책 대부분을 덮으면서 토큰 폭증은 막음. (3단계에서 1/4/10 비교 자리)
- **SIMILARITY_THRESHOLD=0.5**: 0.5 미만(주제 안 맞음)을 잘라 범위 밖 질문에 무관 정책이 끼는 걸 방지. 단 작은 임베딩 모델(0.6b)이라 정답 문서도 가끔 0.5 밑으로 떨어져 Fallback되는 취약성 관찰(아래 step2 참고).
- **임베딩 모델 qwen3-embedding:0.6b(1024dim) ↔ pgvector dimensions=1024 일치**: 불일치 시 조용히 실패하므로 핵심.
