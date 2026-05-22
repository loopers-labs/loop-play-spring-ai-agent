# Support Triage API — 시나리오 3종 응답 결과


> 엔드포인트: `POST /api/v1/support`

---

## 시나리오 1: 배달 위치 문의

**요청**
```text
주문번호 2024-1234 배달 어디쯤에 있어요?
```

**응답**
```json
{
  "summary": "배달 진행 상황을 안내드리기 위해 주문번호와 연락처 끝 4자리를 알려주시겠어요?",
  "category": "DELIVERY",
  "urgency": "NORMAL",
  "nextAction": "주문 상태 조회 요청",
  "neededInfo": [],
  "escalationRequired": false
}
```

**검토**
- category, urgency: 정상
- neededInfo가 빈 배열인데, 주문번호는 메시지에 있으나 연락처 끝 4자리는 아직 없음 → summary에서 구두로 물어보고 있지만 `neededInfo`에 `"연락처 끝 4자리"`가 포함되지 않은 것은 개선 여지 있음

---

## 시나리오 2: 주문 취소 + 환불 기간 문의

**요청**
```text
방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?
```

**응답**
```json
{
  "summary": "취소 요청을 확인했습니다. 현재 주문 상태에 따라 취소 가능 여부와 환불 절차가 달라지므로, 먼저 주문 상태를 확인한 후 안내드리겠습니다.",
  "category": "ORDER",
  "urgency": "NORMAL",
  "nextAction": "주문 상태 조회 요청",
  "neededInfo": [],
  "escalationRequired": false
}
```

**검토**
- 시점 단정 표현("3일 이내" 등) 없음: 금지 규칙 준수
- 보상 약속 없음: 금지 규칙 준수
- category가 `ORDER`로 분류됨 — 취소 문의이므로 적절. `REFUND`보다 먼저 주문 상태 확인이 필요한 단계
- neededInfo가 빈 배열 — 주문번호가 없는데도 요청하지 않음: 개선 여지 있음

---

## 시나리오 3: 음식 손상 — 포장 훼손

**요청**
```text
라이더가 음식을 엎었다는데 보상 받을 수 있나요?
```

**응답**
```json
{
  "summary": "배달 중 음식이 손상되어 정말 불편하셨겠습니다. 정확한 확인을 위해 손상된 음식 사진을 보내주시면 빠르게 확인드리겠습니다.",
  "category": "FOOD_QUALITY",
  "urgency": "HIGH",
  "nextAction": "추가 정보 확인",
  "neededInfo": ["포장 훼손 사진"],
  "escalationRequired": false
}
```

**검토**
- 보상 약속 없이 증빙 요청으로 우회: 금지 규칙 준수
- 일방적 책임 추궁 없음("라이더 잘못입니다" 같은 표현 없음): 금지 규칙 준수
- category=FOOD_QUALITY, urgency=HIGH, neededInfo=["포장 훼손 사진"]: 모두 예상값과 일치
