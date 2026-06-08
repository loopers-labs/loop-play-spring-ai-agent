# 3단계 — 뒤바꿈 rag(5) → memory(10) 2턴 대화

## 1턴: 주문번호 2024-1234 배달 어디?
```
현재 배달 상태는 배달 중이며, 라이더의 위치는 역삼역 사거리 부근입니다. 예상 도착 시간은 2026년 6월 7일 1시 19분 43초입니다. 다른 정보가 필요하시면 알려주세요.
```

## 2턴: 아까 그 주문 환불 돼요?
```
해당 주문번호 2024-1234를 찾지 못했습니다. 다시 확인하신 후 알려주시면 도와드리겠습니다.
```

## 세션 메모리 (chain-broken)
```json
[{"type":"user","text":"주문번호 2024-1234 배달 어디?\n\nContext information is below, surrounded by ---------------------\n\n---------------------\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n"},{"type":"assistant","text":"현재 배달 상태는 배달 중이며, 라이더의 위치는 역삼역 사거리 부근입니다. 예상 도착 시간은 2026년 6월 7일 1시 19분 43초입니다. 다른 정보가 필요하시면 알려주세요."},{"type":"user","text":"아까 그 주문 환불 돼요?\n\nContext information is below, surrounded by ---------------------\n\n---------------------\n# 환불 기본 정책\n\n배달에서 주문 환불은 **주문 상태**와 **사유**에 따라 다르게 처리됩니다.\n\n## 환불 가능 케이스\n\n- **조리 시작 전 취소**: 주문 상태가 CREATED 또는 ACCEPTED인 경우, 고객 요청으로 전액 즉시 취소/환불 가능합니다.\n- **음식 누락 / 오배송**: 배달 완료 후에도 사진 등 증빙을 확인하는 조건으로 전액 또는 해당 메뉴 부분 환불이 가능합니다.\n- **배달 지연 과도 (60분 이상)**: 예상 시간 기준 60분 이상 지연된 경우 배달비 환불 또는 쿠폰 보상이 가능합니다.\n\n## 환불 불가 또는 제한적 처리 케이스\n\n- **조리 시작 이후 단순 변심**: 조리가 시작된(COOKING) 이후의 단순 변심 취소는 전액 환불이 어려우며, 가게와 조율이 필요합니다.\n- **배달 완료 후 24시간 초과**: 배달 완료 후 24시간이 지난 단순 맛 불만족은 환불 대상이 아닙니다. 리뷰로 의견을 남겨 주세요.\n\n## 환불 소요 기간\n\n- 카드 결제: 카드사에 따라 **최대 7영업일**이 소요될 수 있습니다.\n- 배달페이 / 머니 충전금: 취소 즉시 복원됩니다.\n\n## 처리 절차\n\n1. 고객센터 또는 앱 내 문의에서 주문번호를 제출합니다.\n2. 증빙(사진 등)이 필요한 경우 상담사가 요청합니다.\n3. 정책 범위 내 건은 당일 처리, 범위 밖 건은 상담원이 개별 조율합니다.\n# 주문 취소 정책\n\n## 자동 취소 가능 조건\n\n앱에서 고객이 직접 취소할 수 있는 조건입니다.\n\n- 주문 상태가 **CREATED** (주문 접수 직후)\n- 주문 상태가 **ACCEPTED** (가게가 접수 확인은 했으나 조리 시작 전)\n\n이 상태에서는 앱의 \"주문 취소\" 버튼으로 즉시 취소되며, 결제 금액은 전액 환불됩니다.\n\n## 자동 취소 불가 조건\n\n- **COOKING** (조리 시작): 조리가 시작된 이후에는 자동 취소가 불가합니다. 상담원 연결이 필요합니다.\n- **DELIVERING** (배달 중): 배달이 시작된 이후에는 수령 후 반품 절차를 따릅니다.\n- **DELIVERED** (배달 완료): 별도의 환불 정책(배달 완료 후 환불)을 따릅니다.\n\n## 취소 요청 시 안내 문구\n\n- 자동 취소 가능 상태: \"취소가 완료되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있습니다.\"\n- 자동 취소 불가 상태: \"조리가 이미 시작되어 자동 취소가 어렵습니다. 상담원 연결로 도와드리겠습니다.\"\n\n## 중복 취소 요청 (멱등성)\n\n이미 취소된 주문에 대해 다시 취소 요청이 들어와도 에러로 응답하지 않고,\n\"해당 주문은 이미 취소된 상태입니다\"로 안내합니다.\n\n## 고객 귀책 취소\n\n- 고객이 주소를 잘못 입력해 반송된 경우: 배달비는 환불 대상이 아닙니다.\n- 고객이 수령을 거부한 경우: 음식값 전액 + 배달비 왕복 비용이 발생할 수 있습니다.\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n"},{"type":"assistant","text":"해당 주문번호 2024-1234를 찾지 못했습니다. 다시 확인하신 후 알려주시면 도와드리겠습니다."}]
```

## promptTokens
```
2026-06-07T01:14:59.672+09:00  INFO 57769 --- [baedal-support-agent] [nio-8080-exec-1] c.b.support.PerformanceLoggingAdvisor    : LLM call elapsedMs=12206 promptTokens=3587 completionTokens=97 totalTokens=3684
```
