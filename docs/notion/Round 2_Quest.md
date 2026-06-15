# Round 2 Quests

> 📝
이 숙제의 목적은 Tool을 만드는 것이 아니라 **Tool의 경계(boundary)를 설계하는 훈련**입니다.
AI로 코드를 생성해도 됩니다. 단, **왜 그렇게 경계를 그었는지**와 **그 경계가 무너지면 어떤 일이 생기는지**를 직접 관찰하고 기록하세요.
> 

---

## 미션 제출 안내

- **제출 방식**: GitHub 레포 push + README 작성
- **제출 마감**: 다음 라운드 첫 수업 시작 전
- **평가 비중**: 코드보다 **설계 결정 문서 / 실패 관찰 기록**의 품질이 더 큰 비중

> 🎯
이번 라운드 숙제의 목적은 "Tool을 만드는 것"이 아니라 **"Tool의 경계를 설계하고 그 근거를 설명하는 것"** 입니다.
AI로 코드를 생성해도 됩니다. 단, **왜 그렇게 경계를 그었는지**와 **그 경계가 무너지면 어떤 일이 생기는지**를 직접 관찰하고 기록하세요.
> 

## 사전 준비

- [ ]  **JDK 17 이상** — `java -version` 으로 확인. 시스템 기본 JDK가 다른 버전이라면 `JAVA_HOME` 을 JDK 17로 지정하거나 `jenv` 등으로 전환
- [ ]  **Round 1 숙제가 완료된 상태** — System Prompt, Structured Output, `PerformanceLoggingAdvisor`가 동작해야 함
- [ ]  Ollama 실행 중, `qwen2.5` 다운로드 완료 (`ollama list`)
- [ ]  Postman / `httpie` / `curl` 중 편한 도구 (`jq` 권장)
- [ ]  IntelliJ IDEA — DEBUG 로그에서 `ToolResponseMessage`를 검색할 일이 많음

---

## 시작하기

```bash

# 2) Ollama 확인
ollama list  # qwen2.5

# 3) 실행
./gradlew bootRun

# 4) Round 1부터 있던 ChatController 헬스체크
curl -X POST <http://localhost:8080/api/v1/chat> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"안녕?"}'

# 5) 각 Java 파일의 // TODO 주석을 찾아 차례로 구현
#    - OrderTools.java          (3개 TODO)
#    - OrderMockService.java    (Mock 데이터 4건 추가)
#    - AssistantController.java (Tool 등록)
#    - SupportController.java   (Tool 등록)
```

**Tool이 호출되지 않을 때 확인할 것:**

1. `.defaultTools(orderTools)` 가 컨트롤러에 들어갔는가?
2. System Prompt(`BaedalPrompt.SYSTEM_PROMPT`)의 `[Tool 사용 규칙]` 섹션이 있는가?
3. Ollama 모델이 `qwen2.5`인가? 더 작은 모델은 Tool Calling이 불안정합니다.
4. 로그 레벨이 `DEBUG`인가? (`application.yml`의 `org.springframework.ai: DEBUG`)

---

## 1단계: Tool 3개 구현 + Mock 데이터 확장 (30점)

**목표**: 세 개의 `@Tool`을 구현하고, 5종 시나리오로 호출이 정확히 분기되는지 검증한다.

### 구현

- [ ]  `starter-code/`를 본인 레포에 복사
- [ ]  `OrderTools.java`의 세 `// TODO`를 모두 채운다:
    - `getOrderDetail(String orderId)` — `@Tool`, `@ToolParam`, 로깅, 변환기 호출
    - `getDeliveryStatus(String orderId)` — 동일 구조
    - `cancelOrder(String orderId, String reason)` — Outcome 4분기
- [ ]  `OrderMockService.java`의 `// TODO`를 채워 **Mock 주문 4건**을 추가
    - `2024-1236` DELIVERED
    - `2024-1237` COOKING
    - `2024-1238` 사전 CANCELED (멱등 테스트용 — `canceledReason`/`canceledAt` 채워둘 것)
    - `2024-1239` ACCEPTED
- [ ]  `AssistantController`와 `SupportController` 양쪽 모두에 `.defaultTools(orderTools)` 등록

### 검증 — 시나리오 5종

아래 시나리오 5종을 `/api/v1/assistant`로 호출하고, **응답 본문**과 **콘솔 Tool 로그**를 README에 붙여라.

> 💡
수업 라이브 데모(`2024-1237`, `2024-1239`)와 **일부러 다른 시드**(`2024-1235`, `2024-1236`)를 사용한다. 같은 Mock 데이터로 반복하기보다 다른 상태(CREATED/DELIVERED)에서도 Tool이 올바르게 동작하는지 스스로 검증하는 것이 목적.
> 

| # | 시나리오 | 기대 Tool | 기대 Outcome / 결과 |
| --- | --- | --- | --- |
| 1 | `"주문번호 2024-1234 배달 어디쯤에 있어요?"` | `getDeliveryStatus` | 라이더 위치 "역삼역 사거리" 포함 |
| 2 | `"주문번호 2024-1234 어떤 메뉴 주문했어요?"` | `getOrderDetail` | 허니콤보/콜라 포함 |
| 3 | `"주문번호 2024-1235 방금 시킨 건데 취소해주세요"` | `cancelOrder` | `CANCELED` |
| 4 | `"주문번호 2024-1236 취소해주세요"` | `cancelOrder` | `NOT_CANCELABLE` (DELIVERED 상태) |
| 5 | `"주문번호 2099-9999 배달 어디예요?"` | `getDeliveryStatus` (null 반환) | LLM이 "찾을 수 없다"고 안내 |

```bash
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'
```

### 설계 결정 문서 (README에 작성)

- [ ]  `OrderDetailView`는 내부 `Order` 클래스의 필드 중 **무엇을 의도적으로 뺐는가?** (예: `deliveryAddress`, `canceledReason`, `riderLocation` 중 일부) 그 이유는?
- [ ]  `@Tool`의 `description`을 **한국어로 썼는가, 영어로 썼는가?** 어떤 기준으로 결정했는가?
- [ ]  `OrderTools`를 **하나의 클래스로 묶었다**. 분리해야 한다면 어떤 기준으로 나눌 것인가? (조회 vs 변경 / 주문 vs 결제) 현재 수준에서는 왜 하나로 충분한가?

### 자가 점검 체크리스트

- [ ]  `./gradlew bootRun`으로 프로젝트가 정상 실행되는가?
- [ ]  시나리오 5종의 응답 본문이 모두 README에 있는가?
- [ ]  콘솔 로그의 `[Tool] getXxx(orderId=...)` 라인을 각 시나리오마다 캡처했는가?
- [ ]  Mock 주문 4건이 실제로 `seed()`에 추가되었는가? (`OrderMockService seeded — 6건` 로그로 확인)
- [ ]  `2024-1238` 주문에 `order.cancel("고객 요청", ...)` 호출이 포함되어 `canceledReason`이 채워져 있는가? (2단계 멱등성 실험 전제)
- [ ]  설계 결정 3개 질문에 대한 "왜?" 답이 README에 있는가?

---

## 2단계: 멱등성 관찰 — cancelOrder를 두 번 부르면

**목표**: `Outcome` 4가지 경로를 모두 실행해 보고, 멱등성 분기를 의도적으로 제거해 그 부재가 어떤 사고로 이어지는지 직접 관찰한다.

### 구현

- [ ]  `cancelOrder`의 Outcome 4가지 경로 모두 확인:
    - `CANCELED` (`2024-1239` ACCEPTED 상태)
    - `ALREADY_CANCELED` (`2024-1238` 사전 취소 / 또는 1단계에서 취소한 주문을 다시 취소)
    - `NOT_CANCELABLE` (`2024-1236` DELIVERED / `2024-1237` COOKING)
    - `NOT_FOUND` (`9999-0000`)
- [ ]  각 경우의 **LLM 자연어 응답**을 README에 붙여라

### 필수 관찰 시나리오

```bash
# (A) 이미 배달 완료 → NOT_CANCELABLE 관찰
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"주문번호 2024-1236 취소해주세요"}'

# (B) 첫 취소 → CANCELED, 같은 주문 재취소 요청 → ALREADY_CANCELED
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"주문번호 2024-1239 취소해주세요"}'
curl -s -X POST <http://localhost:8080/api/v1/assistant> \\
  -H "Content-Type: application/json" \\
  -d '{"message":"주문번호 2024-1239 진짜 취소됐어요? 한 번 더 취소해주세요"}'
```

### 실패 관찰 — 멱등성 분기 제거 실험

> ⚠️
형식적인 "안 됐어요"가 아니라, **시스템이 어떻게 망가지는지** 출력 자체를 그대로 기록하는 것이 핵심입니다.
> 
- [ ]  `cancelOrder`의 `ALREADY_CANCELED` 분기(이미 `OrderStatus.CANCELED`인지 체크하는 부분)를 **통째로 제거**하라
- [ ]  같은 주문(`2024-1239`)을 연속 2회 취소 요청하라
    - [ ]  코드는 어떻게 동작하는가? (예외 / 잘못된 값 / 두 번째 호출이 다른 분기로 흘러가는가)
    - [ ]  LLM의 자연어 응답은 어떻게 바뀌는가?
    - [ ]  로그에서 `canceledReason`은 어떻게 덮어쓰이는가?
- [ ]  **"고객에게 어떤 오해를 줄 수 있는가?"** 를 3가지 이상 작성
- [ ]  멱등성 분기를 복원한 뒤, **"이 로직이 없었다면 프로덕션에서 어떤 장애가 생겼겠는가"** 를 3가지 이상 작성
(예: 결제 이중 취소 / 포인트 이중 환급 / 사장님에게 취소 알림 두 번 / ...)

### 설계 결정 문서 — Outcome enum의 근거

- [ ]  현재 `Outcome`은 `CANCELED / ALREADY_CANCELED / NOT_CANCELABLE / NOT_FOUND` 4개다. **왜 4개인가?** `UNKNOWN`이나 `FAILED` 같은 값을 넣지 않은 이유는?
- [ ]  배달 운영에서 실제로 추가될 법한 Outcome을 **2개 이상** 상상하고, 각각의 시나리오와 함께 작성
(예: `REQUIRES_AGENT` / `COOLING_OFF` 등)
- [ ]  멱등성의 **세 가지 수준**(에러 / 무시 / 같은 응답 재전달) 중 `cancelOrder`에 "같은 응답 재전달"을 택한 이유는? 다른 상황에서 "에러"가 더 적절한 예를 하나 들어라

### 자가 점검 체크리스트

- [ ]  Outcome 4가지를 모두 실행하고 LLM 응답을 기록했는가?
- [ ]  멱등성 분기 제거 후 관찰 결과(코드 동작 / LLM 응답 / `canceledReason` 덮어씌움)가 README에 있는가?
- [ ]  "고객 오해 3가지" + "프로덕션 장애 3가지"가 작성되어 있는가?
- [ ]  Outcome enum 설계 근거와 신규 Outcome 2개 아이디어가 작성되어 있는가?

---

## 3단계: Tool description 실험 — LLM이 Tool을 안 부르게 만들기 (20점)

**목표**: `description`이 **LLM에게 보여주는 유일한 API 문서**임을 정량적으로 체감한다.

### 구현

`OrderTools.getDeliveryStatus`의 `description`을 **3가지 버전**으로 바꿔가며, 같은 질문(`"주문번호 2024-1234 배달 어디쯤이에요?"`)을 **5회씩** 호출하라.

| 버전 | description 내용 |
| --- | --- |
| A (기준) | 강의 자료의 "정상" description |
| B (빈약) | `"배달 정보 조회"` 한 줄만 |
| C (오해 유발) | `"주문번호 조회용. 메뉴와 결제 금액만 반환한다."` — 실제 기능과 안 맞는 설명 |

각 버전마다 **LLM이 Tool을 호출했는지 / 안 했는지**를 로그로 확인하라.

- [ ]  버전 B: `[Tool] getDeliveryStatus` 로그가 안 찍히거나 줄어드는가?
- [ ]  버전 C: LLM이 `getOrderDetail`을 대신 부르거나, 엉뚱한 답을 하는가?

### 정량 비교 표 (README에 작성)

| 버전 | Tool 호출 횟수 (5회 중) | 응답에 "역삼역 사거리" 포함 횟수 | 비고 |
| --- | --- | --- | --- |
| A | ? | ? |  |
| B | ? | ? |  |
| C | ? | ? |  |

### 설계 결정 문서 (README에 작성)

- [ ]  버전 C에서 LLM이 어떤 행동을 했는가? (Hallucination / 잘못된 Tool / 질문 회피 / ...)
- [ ]  **description 작성 시 반드시 포함해야 할 항목 4가지**를 본인 언어로 정리하라
(강의 자료의 4가지 — 무엇/언제/입력/실패 — 을 그대로 쓰지 말고, 본인이 실험으로 체감한 **중요도 순으로 재배열**해 설명)
- [ ]  description이 "오래된 주석"처럼 실제 Tool 동작과 어긋나게 되는 상황을 프로덕션에서는 어떻게 막을 것인가? (힌트: 테스트, PR 리뷰, Contract Test, ...)

### 자가 점검 체크리스트

- [ ]  세 버전의 description 전문이 README에 있는가?
- [ ]  각 버전별 Tool 호출 횟수 / 응답 내용이 수치 표로 기록되어 있는가?
- [ ]  버전 C에서 LLM이 Hallucination 했는지 여부가 구체적으로 기록되어 있는가?
- [ ]  description 필수 항목 4가지 + "오래된 주석" 방지 대책이 작성되어 있는가?

---

## 4단계: Observability + AI 코드 리뷰

**목표**: Tool 왕복을 로그로 직접 관찰하고, AI가 만든 Tool 코드의 프로덕션 결함을 비판적으로 검토한다.

### 구현 — `PerformanceLoggingAdvisor`로 Tool 왕복 관찰

- [ ]  `/api/v1/assistant`에 `"주문번호 2024-1234 배달 어디쯤에 있어요?"`를 보내고, 콘솔 로그에서 다음 4가지를 찾아 README에 기록:
    - 1차 LLM 호출 — Tool 정의(JSON 스키마)가 포함된 프롬프트 전문
    - `[Tool] getDeliveryStatus(orderId=2024-1234)` 시점의 로그
    - 2차 LLM 호출 — `ToolResponseMessage`가 추가된 프롬프트
    - 최종 `LLM 호출 완료 — XXXms | 입력 토큰: YY | 출력 토큰: ZZ`
- [ ]  **입력 토큰 차이를 수치로 비교:**

| 엔드포인트 | 같은 질문 | 입력 토큰 | 출력 토큰 | 응답 시간 |
| --- | --- | --- | --- | --- |
| `/api/v1/chat` (Round 1, Tool 없음) | `"안녕하세요"` | ? | ? | ? |
| `/api/v1/assistant` (Round 2, Tool 3개 등록) | `"안녕하세요"` | ? | ? | ? |
- [ ]  두 엔드포인트의 입력 토큰 차이가 **무엇에서 오는지** 설명하라
- [ ]  Tool이 실제로 호출되는 시나리오(`"2024-1234 어디쯤?"`)에서는 Round 1 대비 **몇 배의 입력 토큰**이 드는지 기록

### AI 코드 리뷰 — 프로덕션 결함 찾기 (README에 작성)

1. AI(ChatGPT, Claude, Cursor 등)에게 아래 프롬프트로 코드를 요청:
    
    > `"Spring AI 1.0으로 배달 주문 취소 Tool을 만들어줘. @Tool 어노테이션을 써야 해."`
    > 
2. 받은 코드에서 **프로덕션에 올리면 안 되는 결함 3개**를 찾아 기록 (아래 힌트 중 3개 이상)
    - 멱등성 없음 (중복 취소 가능)
    - 예외를 그대로 throw (LLM이 Fallback 못 함)
    - 내부 엔티티를 그대로 반환 (민감 정보 노출 / 토큰 낭비)
    - 권한 검증 없음 (누구나 취소 가능)
    - `description`이 부실 (영어 한 줄 / 언제 호출할지 명시 없음)
    - 로깅 없음 (감사(Audit) 불가)
    - Outcome 구분 없음 (boolean 반환)
3. 각 결함마다 **이번 수업에서 배운 방식으로 어떻게 고칠지** 개선 방안 작성
4. AI가 생성한 원본 코드와 본인의 개선 코드를 함께 README에 첨부

### 자가 점검 체크리스트

- [ ]  Tool 정의가 포함된 1차 프롬프트 전문이 README에 있는가?
- [ ]  Round 1 vs Round 2 입력 토큰 수치 비교 표가 있는가?
- [ ]  AI 생성 코드의 원본이 README에 첨부되어 있는가?
- [ ]  결함 3개 + 각각의 개선 방안이 구체적으로 작성되어 있는가?

---

## 공통: 학습 기록 (10점)

README 하단에 다음 세 단락을 작성하라:

- [ ]  **"내가 배운 것"** — Round 2에서 새롭게 알게 된 점. (Tool Calling, 멱등성, description, 판단/실행 분리 등 중 **본인이 직접 체감한 것** 위주로)
- [ ]  **"의문점"** — 아직 해결되지 않은 궁금증
(예: "Tool이 동시에 여러 개 호출될 때 순서는? 트랜잭션은?")
- [ ]  **"Round 3에 시도하고 싶은 것"** — Round 3 Chat Memory와 연결할 아이디어
(예: "`그거 취소해주세요` 같은 지시 대명사를 해결하려면 Memory에 최근 orderId를 넣어야 할 것 같다")

## 제출 가이드

1. 본인 GitHub 레포에 push
2. README.md에 다음을 모두 포함:
    - 1단계 시나리오 5종 응답 + Tool 로그
    - Mock 데이터 4건 추가 코드
    - 2단계 Outcome 4가지 응답 + 멱등성 제거 실험 기록
    - 3단계 description 3버전 + 정량 비교 표
    - 4단계 Tool 왕복 로그 + Round 1 vs Round 2 토큰 비교표 + AI 코드 리뷰 (원본 + 개선 코드)
    - 공통: "내가 배운 것 / 의문점 / Round 3 아이디어"
3. 다음 수업 전까지 PR 또는 레포 링크 제출

### PR/제출물 체크

- [ ]  제목: `[Round 2] {본인 이름} - {몇 단계까지 완료}`
- [ ]  본문에 어디까지 완료했고 어디서 막혔는지 명시
- [ ]  API Key 등 민감 정보가 커밋에 포함되지 않았는지 확인
- [ ]  `./gradlew build` 로 컴파일 에러 없는지 마지막으로 확인
- [ ]  **변경 파일 20개 이하** — 넘으면 `.gitignore`에 `build/`, `.gradle/`, `.class`, `.idea/`, `.iml` 추가
- [ ]  본인 PR을 본인이 한 번 셀프 리뷰 — "위험 신호" 7가지에 걸리는지 확인 ([리뷰 가이드](https://www.notion.so/REVIEW_GUIDE.md) 참조)

### Merge 조건

- **페어 리뷰어 2명의 Approve** 가 필요합니다
- Approve 기준은 [리뷰 가이드](https://www.notion.so/Approval-35d5f89164b4809eb0fff8a59093cb44?pvs=21)의 3축 (설계 결정의 근거 / 실패 관찰의 구체성 / 다음 라운드 연결)
- **Round 2의 핵심 평가축**: (1) description A/B/C 정량 비교 수치 (2) 멱등성 분기 제거 후 `canceledReason` 덮어쓰임 + LLM 응답 인용
- 페어 리뷰어로 배정되면 **48시간 내 첫 코멘트**, 다음 라운드 첫 수업 24시간 전까지 결론

> 💡
Round 1 피드백에서 "AI 코드 리뷰가 피상적이었던 케이스"가 부분 점수 1위였습니다. 본인 PR을 셀프 리뷰할 때 4단계의 AI 코드 리뷰가 "이번 라운드 도구로 어떻게 고칠지"까지 적혔는지 확인하세요.
> 

---

> 💡
Tool 호출 관련 질문은 **"어떤 질문을 보냈는데 어떤 Tool이 호출되기를 기대했고, 실제로는 (어떤 Tool이 / 혹은 아무 Tool도) 호출됐다"** 형식으로 해주세요. 대부분 이 단계에서 문제가 스스로 드러납니다.
>