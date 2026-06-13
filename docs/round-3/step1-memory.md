# Round 3 · 1단계 — ChatMemory 3레이어 + X-Session-Id + 지시 대명사

> `/api/v1/assistant`(+`/api/v1/support`)에 `MessageChatMemoryAdvisor` 연결, `X-Session-Id` 헤더 → `ChatMemory.CONVERSATION_ID`.
> 3레이어: `InMemoryChatMemoryRepository`(저장) → `MessageWindowChatMemory`(maxMessages=20 윈도우) → `MessageChatMemoryAdvisor`(order 10).
> ChatClient는 `AssistantChatClientConfig`에서 1회 build (Builder 누적버그 회피), conversationId는 요청별 `.advisors(a -> a.param(...))`.

## 검증 — 시나리오 5종 (모두 PASS)

| # | 시나리오 | 기대 | 결과 |
|---|----------|------|------|
| 1 | A:"2024-1234 어디쯤?" → "그거 언제 도착?" | 2회차 1234 맥락 유지 | ✅ "그거"=1234 해결, ETA 응답 |
| 2 | A:"2024-1234 취소" → "그거 말고 2024-1235 취소" | 대상 1235로 전환 | ✅ 2회차 cancelOrder 대상 1235 |
| 3 | A:"2024-1234 어디?" → "아까 그 주문 언제 도착?" | memory서 orderId 추출 | ✅ 1234 추출 응답 |
| 4 | A:"2024-1234..." → **B**:"그 주문 어디?" | B에 맥락 없음 | ✅ B "어떤 주문을 말씀하시는 건가요?" 되물음 |
| 5 | A:"2024-1234..." → DELETE A → "그거" | 맥락 소멸 | ✅ DELETE 후 memory `[]` |

### 시나리오 1 — 지시 대명사 (`X-Session-Id: s1A`)
```
turn1 "2024-1234 배달 어디쯤?"  → [Tool] getDeliveryStatus(2024-1234) → "역삼역 사거리 부근 배송 중, 도착 7:29:48"
turn2 "그거 언제 도착해요?"      → "예상 도착 7시 30분" (그거=1234, tool 재호출 없이 이력 재사용)
GET /session/s1A/messages → USER, ASSISTANT, USER, ASSISTANT 4건 순서대로 저장 확인
```
시스템 프롬프트 `[대화 맥락 사용 규칙]`의 "이미 조회한 정보는 이력에서 재사용"이 동작.

### 시나리오 4 — 세션 격리 (가장 강력한 증거)
```
A "2024-1234 어디?"  → getDeliveryStatus(2024-1234), "역삼역 사거리…"
B "그 주문 어디?"     → "어떤 주문을 말씀하시는 건가요? 가장 최근에 언급한 주문번호를 알려주시면…"
GET /session/s4B/messages → B의 USER/ASSISTANT 2건만 (A의 1234 흔적 없음)
GET /session/ids          → ["s3A","s4B","s4A","s1A","s2A"] (세션별 독립 저장)
```
→ 같은 ChatClient 빈을 공유해도 `conversationId`(=X-Session-Id)로 완전 분리. B는 A의 맥락을 전혀 못 봄.

### 시나리오 5 — 삭제
```
turn1 "2024-1234 어디?"          → memory 2건
DELETE /api/v1/session/s5A       → http 200, 로그 [Session] clear sessionId=s5A
GET /session/s5A/messages        → []   ← 비워짐 증명
turn2 "그거 언제 도착?"          → )((((getDeliveryStatus {"orderId":"2024-1234"})))) (누출)
```
**관찰**: memory는 정상 삭제(`[]`). 다만 빈 메모리에서 "그거"를 물으니 모델이 되묻지 않고 **tool description의 예시 ID(`2024-1234`)** 를 끌어다 호출 시도(텍스트 누출). 맥락이 없을 때의 degrade — "그거"의 referent가 없으면 되물어야 하지만 작은 모델은 프롬프트 예시를 잡았다.

## 부수 관찰 — qwen2.5 tool-call 누출은 멀티턴에서도 지속
시나리오 2(취소)에서 `)((((cancelOrder {"orderId":"2024-1235"...` 텍스트 누출. 다만 **대상이 1234→1235로 전환된 것 자체는 맥락이 살아있다는 증거**(memory가 이전 턴을 기억하고 새 orderId로 갱신).

---

## 설계 결정 문서 (1단계) — *초안, 제출 전 검토*

**Q. MAX_MESSAGES를 20으로 선택한 근거**
배달 상담 1건은 평균 3~6턴(USER+ASSISTANT = 6~12 메시지). 20이면 약 10턴(USER/ASSISTANT 합산)을 보존 → 한 상담 세션을 거의 다 덮으면서, 지시 대명사가 참조하는 "최근 주문번호"가 윈도우 밖으로 밀려날 위험이 낮다. 2단계에서 1 / 20 / MAX로 실험해 이 가정을 검증한다.

**Q. `X-Session-Id` 없을 때 "default" 폴백의 위험 시나리오 2개**
1. **앱 미업데이트 구버전 클라이언트** — 헤더를 안 보내는 여러 고객이 전부 "default" 세션을 공유 → 서로의 주문 맥락이 섞임(개인정보 사고). A의 "2024-1234"가 B의 "그거"로 해석될 수 있음.
2. **어뷰저의 의도적 헤더 누락** — 헤더를 빼고 호출해 "default" 공용 세션에 접근, 직전에 그 세션을 쓴 다른 고객의 대화 이력을 엿봄.

**Q. 세션 식별 실무 대안 비교 (배달 상담 적용)**

| 방식 | 장점 | 단점 (배달 상담) |
|------|------|------|
| HTTP 헤더(현재) | 구현 단순, stateless | 클라이언트가 값을 정함 → 위조/충돌 위험 |
| 쿠키 | 브라우저 자동 전송, 서버 발급 가능 | 모바일 앱/서버-서버 호출엔 부자연 |
| JWT 클레임(sub) | **서버 서명 검증** → 위조 불가, 인증과 일원화 | 토큰 발급 인프라 필요 |
| URL 경로 | 명시적, 디버깅 쉬움 | URL에 세션 노출(로그/리퍼러 유출), 공유 위험 |

**Q. 세션 ID를 클라이언트가 정하게 하는 보안 리스크 + 방어**
클라이언트가 임의 sessionId를 보내면 **남의 세션 ID를 추측·도용**해 대화 이력 열람/오염 가능(IDOR). 방어: 서버가 세션 ID를 **발급**(UUID, 추측 불가), 또는 **JWT 서명으로 conversationId를 검증**해 클라이언트가 바꿔도 서버가 거부. 최소한 sessionId를 인증 주체(고객 ID)에 **바인딩**해 교차 접근을 차단.
