# Step 1 — 시나리오 3종 응답

`/api/v1/support` (구조화 출력) 호출 결과. `BaedalPrompt.SYSTEM_PROMPT` + `BeanOutputConverter`가 `SupportResponse` JSON 스키마 자동 주입.

조건: Spring Boot warm cache 상태. temperature 0.3 (application.yml 디폴트).

---

## 시나리오 1 — 배달 위치 문의

**입력**: `"주문번호 2024-1234 배달 어디쯤에 있어요?"`

**응답**:
```json
{
  "summary": "주문번호 2024-1234의 배달 위치에 대해 문의하셨습니다.",
  "category": "DELIVERY",
  "urgency": "NORMAL",
  "nextAction": "현재 배달 상태를 확인합니다.",
  "neededInfo": ["배달 현황"],
  "responsibleParties": ["RIDER"],
  "suspicionSignals": []
}
```

**관찰**: 표면적 의도 → DELIVERY 정확 분류. `responsibleParties: [RIDER]` 위치 추적 책임. `suspicionSignals: []` 정상 문의에는 비움 (LLM 자기-자제).

---

## 시나리오 2 — 취소·환불 문의

**입력**: `"방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"`

**응답**:
```json
{
  "summary": "주문 취소를 진행하겠습니다.",
  "category": "ORDER",
  "urgency": "NORMAL",
  "nextAction": "주문 취소 요청 처리",
  "neededInfo": ["주문번호"],
  "responsibleParties": ["PLATFORM"],
  "suspicionSignals": []
}
```

**관찰** — 다중 의도 한계:
- 메시지에 **2개 의도**: (1) 주문 취소, (2) 환불 타이밍
- LLM이 **ORDER로만 분류**, summary도 환불 타이밍 무시
- 단일 Category 설계의 알려진 한계. 다음 라운드 회수 자리.

---

## 시나리오 3 — 라이더 사고 클레임 (옵션 B 검증)

**입력**: `"라이더가 음식을 엎었다는데 보상 받을 수 있나요?"`

**응답**:
```json
{
  "summary": "라이더가 음식을 엎었다는 사항으로 보상이 가능한지 문의해주셨습니다.",
  "category": "COMPLAINT",
  "urgency": "NORMAL",
  "nextAction": "추가 정보를 제공해주세요.",
  "neededInfo": ["주문번호", "배달 주소"],
  "responsibleParties": ["RIDER", "PLATFORM"],
  "suspicionSignals": []
}
```

**관찰** — 옵션 B 설계 데이터 입증:
- `category: COMPLAINT` ✅ (신설 카테고리가 정확히 작동. 기존 5개 enum이었으면 REFUND로 빨려들어 사고형 환불 처리 라인 누락)
- `responsibleParties: [RIDER, PLATFORM]` ✅ **다중 책임 표현** (단일 enum이었으면 PLATFORM 책임 놓침)

---

## 분류 비교

| 시나리오 | category | responsibleParties |
|---|---|---|
| 1 (배달 위치) | DELIVERY | [RIDER] |
| 2 (취소·환불) | ORDER | [PLATFORM] |
| 3 (사고 클레임) | COMPLAINT | [RIDER, PLATFORM] |

→ 3개 모두 다른 카테고리 + 다른 책임자. 단계 1 자가점검 "시나리오 3종의 category/urgency 가 시나리오별로 다르게 분류된다" 통과.

## Urgency 보수성 관찰

3개 모두 `urgency: NORMAL`. 시나리오 2(시간 민감) / 3(사고)는 HIGH일 만한데. qwen2.5의 보수성. → 단계 2 Experiment C에서 `[응답 포맷]`에 urgency 판정 4단계 명시로 부분 개선.

## 응답 시간 (PerformanceLoggingAdvisor 측정)

- Cold (Ollama 모델 로딩 포함): ~50초
- Warm 첫 호출: ~28초
- Warm 후속: 19-25초 (KV 캐시 효과)
