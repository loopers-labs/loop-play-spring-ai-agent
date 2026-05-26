# Step 2 부록 — Temperature 비교 (0.0 / 0.3 / 0.7)

같은 모호 메시지 + simplified prompt + ollama 직접 호출 (Spring AI 우회). 5회씩 반복.

**조건**:
- Model: qwen2.5
- Message: `"음식이 이렇게 다 식어서 왔는데 이걸 누가 먹어요?"`
- Simplified system prompt (category enum 6개 + urgency 4단계만 강제, BaedalPrompt 미사용)
- Ollama `format=json` 옵션

---

## 결과

| temperature | category | urgency |
|---|---|---|
| **0.0** | DELIVERY 5/5 | HIGH 5/5 |
| **0.3** (default, 채택) | DELIVERY 5/5 | HIGH 5/5 |
| **0.7** | DELIVERY 5/5 | HIGH 5/5 |

3개 온도 모두 **동일한 결과** — `{"category": "DELIVERY", "urgency": "HIGH"}` 5/5.

## 관찰 — 이 메시지로는 temperature 효과 측정 불가

이 메시지의 시그널이 너무 강해서 (배달 + 식음 + 감정 톤) temperature 변동이 묻힘. 분류 일관성 측면에서 0.0/0.3/0.7 차이 없음.

**원래 가설**:
- 0.0 = 완전 deterministic, 동일 응답
- 0.3 = 분류 안정성 + 표현 다양성 균형
- 0.7+ = 변동 큼, 분류 흔들 가능

→ 이 메시지로는 가설 검증 불가. **더 모호한 보더라인 메시지** (예: 짧은 명사형, 의도가 약한 표현)에서 차이 나타날 가능성.

## 0.3 채택 정당성 (이 실험과 별개 근거)

- 0.0 = 응답 표현 다양성 0 → 매번 동일 문장 = UX 손실
- 0.7+ = 모호 케이스에서 분류 흔들림 가능 (이 메시지에선 안 보이지만 보더라인에서 보일 가능성)
- **0.3 = 분류 안정성 + 표현 다양성 균형점** (이론적 합의)

## Limitation

- 단일 메시지로 측정 = 통계적 검증력 약함
- 페어 PR #6 (배정은)은 **90 calls** (3 temperature × 3 케이스 × 10회) + 5개 지표로 측정. 우리 5회 × 1 메시지는 약함
- 다음 라운드 회수: 더 다양한 메시지 + 더 큰 표본 + 정확성 metric (ground truth 필요)

## 페어 PR #3 (홍세영)의 결과 비교

홍세영도 같은 도메인에서 측정:
- 0.0: consistency 1.0이지만 **경계 케이스 오분류 반복**
- 0.3: 80% consistency
- 1.0: consistency 0.6

→ 홍세영은 운영자 모범 grid 예시 (`0.0/0.3/0.7 → 70/85/60%`)와 같은 패턴. 우리는 같은 결과 못 잡음 (메시지 선택 차이). Round 2에서 더 모호한 메시지로 재실측 가치.

## raw 데이터

- `exp-temp-zero.json` / `exp-temp-mid.json` / `exp-temp-high.json` (각 5 runs + summary)
- 모두 `{"category": "DELIVERY", "urgency": "HIGH"}` 5/5

> raw .json 파일은 별도 보관 (재현성·hash 검증용). git history에서 추적 가능.
