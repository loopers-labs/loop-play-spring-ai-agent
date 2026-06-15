# 1단계 설계: 기본 API + System Prompt + Structured Output

**날짜**: 2026-05-15  
**범위**: `SupportController`, `SupportResponse`, `BaedalPrompt`

---

## 1. 아키텍처

`POST /api/v1/support` 엔드포인트는 `ChatClient.Builder`를 사용해 Ollama(qwen2.5)를 호출하고, Spring AI의 `.entity(SupportResponse.class)`로 JSON → Java 레코드 변환을 받는다.

```text
[Client] → POST /api/v1/support {message}
    → ChatClient (SYSTEM_PROMPT + user message)
    → Ollama qwen2.5
    → BeanOutputConverter (JSON schema 주입 + 파싱)
    → SupportResponse (structured output)
→ [Client]
```

Spring AI는 `.entity()` 호출 시 `SupportResponse`의 JSON 스키마를 자동 생성해서 프롬프트에 주입하고, LLM 응답을 파싱해 Java 타입으로 반환한다.

---

## 2. SupportController 구현

```java
@PostMapping
public SupportResponse triage(@RequestBody ChatRequest req) {
    return builder
        .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
        .build()
        .prompt()
        .user(req.message())
        .call()
        .entity(SupportResponse.class);
}
```

---

## 3. SupportResponse 설계

### 추가 필드: `estimatedResolutionMinutes` (Integer)

**선택 근거**: 고객이 "얼마나 걸려요?"를 가장 자주 묻는다. `urgency`는 우선순위를 나타낼 뿐 시간 정보를 담지 않는다. 숫자형 필드로 구조화하면 프론트엔드에서 "약 N분 소요 예정" 형태로 바로 사용 가능하고, LLM이 urgency + category를 함께 고려해 추론하도록 유도한다.

예상값 기준:
- 배달 위치 확인: 5분
- 주문 취소: 10~30분
- 환불: 1~3일 (1440~4320분)
- 라이더 사고 보상: 수일 이상 (조사 필요 시 null)

### Category enum 수정: `COMPLAINT` 추가

**현재**: `ORDER, DELIVERY, REFUND, PAYMENT, ETC`

**문제**: 시나리오 3 "라이더가 음식을 엎었다"는 환불 요청이기도 하지만 본질은 서비스 피해 불만이다. 억지로 `REFUND`나 `ETC`에 넣으면 하위 라우팅(담당팀 배정 등)이 부정확해진다.

**수정**: `ORDER, DELIVERY, REFUND, PAYMENT, COMPLAINT, ETC`

`COMPLAINT`는 음식 품질 불만, 파손, 오배달, 라이더 태도 등 "배상/보상을 요구하는 피해 경험" 전반을 담는다.

---

## 4. BaedalPrompt [금지] 규칙 설계

### 선택한 3가지와 근거

| 규칙 | 근거 | 빼면 생기는 사고 |
|------|------|----------------|
| 타사 플랫폼 추천 금지 | 경쟁사 유도 방지, 브랜드 신뢰 보호 | "쿠팡이츠가 더 빠르지 않아요?"에 AI가 동의 |
| 개인정보 노출 금지 | 개인정보보호법 의무 | 고객 요청 시 라이더 연락처 노출 |
| 쿠폰/보상 약속 금지 | 무권한 약속 방지, 법적 리스크 제거 | "5000원 쿠폰 드릴게요"가 법적 구속력 발생 |

3가지 모두 단순 품질 규칙이 아니라 **실제 운영에서 법적·비즈니스 사고를 막는 안전장치**다. 어느 하나도 제거 불가.

### 추가할 규칙: 의료·법률 조언 금지

**근거**: 음식 알레르기 반응, 식중독 의심 등 건강 피해를 AI가 임의로 판단하면 의료 과실에 준하는 리스크가 생긴다. 현재 [규칙]의 "확인이 필요합니다"로 어느 정도 커버되지만, 명시적 금지 없이는 LLM이 "증상이 경미하니 괜찮을 것 같습니다" 같은 판단을 내릴 수 있다.

**추가 문구**: "건강 피해(알레르기, 식중독 등)에 대해 의료적 판단을 하지 않으며, 즉시 의료기관 방문을 안내합니다."

---

## 5. 구현 순서 (C 방식)

1. `SupportResponse` — `estimatedResolutionMinutes` 추가, `COMPLAINT` 추가
2. `BaedalPrompt` — [금지]에 의료·법률 조언 금지 추가
3. `SupportController` — TODO 구현
4. 앱 실행 후 3가지 시나리오 curl 호출
5. 응답 JSON → README 붙여넣기 + 설계 결정 문서 작성