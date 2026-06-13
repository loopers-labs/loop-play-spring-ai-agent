# Round 2 · 4단계 — Observability + AI 코드 리뷰

## Tool 왕복 관찰 — PerformanceLoggingAdvisor

`PerformanceLoggingAdvisor`(Round 1 자작, `[LLM] ... promptTokens=...`)로 입력 토큰을 측정.
Spring AI는 Tool 실행을 포함한 전체 루프가 끝난 뒤 최종 응답을 돌려주므로, advisor가 보는 `promptTokens`는 **tool 결과가 되먹임된 최종 모델 호출** 기준이다.

| 호출 | promptTokens | completionTokens | 비고 |
|------|:---:|:---:|------|
| `/api/v1/chat` "안녕하세요" (Round 1, no tools, no advisor) | (로그 없음) | — | advisor 미부착 → 입력은 사실상 유저 메시지뿐(수십 토큰) |
| `/api/v1/assistant` "안녕하세요" (tools 등록, **미호출**) | **1,569** | 49 | system prompt + **3개 @Tool JSON 스키마**가 매 요청 주입 |
| `/api/v1/assistant` "2024-1234 배달 어디?" (getDeliveryStatus **왕복**) | **3,298** | 92 | tool 결과 + 대화 이력 되먹임 → 미호출 대비 **약 2.1배** |
| `/api/v1/support` "2024-1234 메뉴?" (structured + tools) | **3,983** | 137 | structured output 스키마까지 더해져 최대 |

### 두 엔드포인트 입력 토큰 차이의 출처
- `/chat`(Round 1)과 `/assistant`(Round 2)의 근본 차이는 **@Tool 스키마 주입**. tool 3개의 이름·description·파라미터 스키마가 system 영역에 직렬화되어 들어가며, 이것만으로 유저 발화 전 ~1,569 토큰이 깔린다.
- tool이 **실제 호출**되면(왕복) 1차 응답의 tool-call 메시지 + tool 결과(ToolResponseMessage)가 2차 호출 입력에 누적 → 1,569 → 3,298.
- `/support`가 가장 큰 이유: 위 tool 스키마 + **SupportResponse Structured Output 스키마**(JSON schema)가 함께 프롬프트에 붙기 때문.

### Round 1 대비 입력 토큰 배수
Round 1의 무도구 호출은 입력이 유저 메시지 수준(수십 토큰). Tool 3개를 얹은 Round 2는 미호출만으로도 ~1,569, 왕복 시 ~3,298. → **tool 도입은 "공짜"가 아니다.** 호출하지 않아도 스키마 상주 비용이 매 요청 발생하므로, tool 개수·description 길이가 곧 토큰 비용이다.

### 부수 관찰 — Structured Output + Tool Calling 충돌
`/support`(둘 다 적용) 호출 시 모델이 `getOrderDetail(orderId=null)`을 호출하고, 정작 orderId("2024-1234")는 결과 JSON의 `neededInfo`에 넣음. "JSON을 만들라"와 "tool을 부르라"를 동시에 지시받자 작은 모델이 tool 호출을 *흉내*만 내고 실제 인자를 누락. → 한 엔드포인트에서 두 메커니즘을 합치는 것은 모델 부담이 큼. (내 Round 1 커스텀 필드 responsibleParties/suspicionSignals는 정상 보존 확인.)

---

## AI 코드 리뷰 — 프로덕션 결함 3개

> 이 tool 코드(운영자 starter + 구현)를 프로덕션 기준으로 비판적 리뷰. "Mock이라 괜찮다"를 걷어내고 실제 서비스로 가정.

### 결함 1 — cancelOrder의 check-then-act 경쟁 조건 (TOCTOU)
`OrderMockService`는 `ConcurrentHashMap`이라 *맵 연산*은 thread-safe지만, `Order` 객체 자체의 상태 변경(`cancel()`)은 **비동기화**다. cancelOrder는 `findById → status 검사 → isCancelable 검사 → cancel()`의 4단계인데 원자적이지 않다. 같은 주문에 동시 cancelOrder 2건이 들어오면 둘 다 `isCancelable()==true`를 통과해 **이중 취소**(이중 환불·이중 알림) 발생. 멱등 분기(ALREADY_CANCELED)도 이 레이스를 막지 못한다(둘 다 CANCELED 이전에 검사 통과).
→ **수정**: 주문 단위 락 / DB 낙관적 락(version 컬럼) / `UPDATE ... WHERE status IN ('CREATED','ACCEPTED')`의 조건부 갱신으로 원자화.

### 결함 2 — 권한 검증 부재 + 순차 ID = IDOR
cancelOrder/getOrderDetail은 **호출자가 그 주문의 주인인지 확인하지 않는다.** 게다가 주문번호가 `2024-1234`처럼 **순차적**이라 enumeration이 자명. 공격자가 `2024-1235, 1236...`를 훑으면 **남의 주문 상세 조회 + 취소**가 가능(IDOR). LLM 경유라도 결국 tool은 인자만 받으면 실행한다.
→ **수정**: 세션/JWT의 고객 ID와 주문 소유자 대조, 주문번호를 비순차(UUID/해시)로, tool 레이어에 인가 컨텍스트 주입.

### 결함 3 — 영속성 없음 + 재시작 시 상태 부활
`@PostConstruct seed()`가 인메모리 Map을 매 기동마다 다시 채운다. 취소·상태 변경은 **재시작 시 전부 소실**되고, 취소했던 주문이 다시 CREATED로 **부활**한다. 배포(=재시작)가 곧 데이터 롤백. 감사 로그도 없어 "누가 언제 취소했나" 추적 불가.
→ **수정**: 실제 저장소(DB)로 이전, 상태 전이 이벤트를 append-only 감사 로그로 기록. (Round 3에서 메모리 영속성을 InMemory→JDBC로 다루는 것과 같은 맥락.)

> 추가 후보(요약): reason 무검증(로그 주입/과대 입력), null 반환의 모호성(not-found vs error 구분 불가 → 타입드 결과 권장), totalAmount를 매 조회 재계산(스냅샷 부재).
