# AI 코드 리뷰

> **과제**: AI에게 "Spring AI로 배달 상담 챗봇을 만들어줘"라고 요청하여 받은 코드의 프로덕션 결함을 비판적으로 검토.

## 받은 코드 (AI 생성)

AI에 다음 프롬프트로 요청:
> "Spring AI로 간단한 배달 상담 챗봇 REST API 만들어줘. 사용자 메시지 받아서 응답 돌려주는 거."

받은 코드:

```java
@RestController
@RequestMapping("/api/chat")
public class DeliveryChatBot {

    private final OpenAiChatClient chatClient;

    public DeliveryChatBot() {
        OpenAiApi api = new OpenAiApi("sk-proj-abc123XYZ");  // API key
        this.chatClient = new OpenAiChatClient(api);
    }

    @PostMapping
    public String chat(@RequestBody Map<String, String> body) {
        String userMessage = body.get("message");
        String prompt = "사용자 메시지: " + userMessage + "\n답변:";
        return chatClient.call(prompt);
    }
}
```

---

## 프로덕션 결함 3가지

### 결함 1 — System Prompt 부재

**문제**:
LLM에 직접 `"사용자 메시지: ... 답변:"` 형태로 raw 메시지를 던진다. 배달 도메인 [역할]/[규칙]/[금지]가 없어서 LLM이 자유롭게 응답한다.

**왜 위험한가** (Round 1 단계 2 [금지] 제거 실험에서 직접 확인):
- 경쟁사 비방 유도 → 자기 플랫폼 깎고 경쟁사 평가 적극 참여
- 협박 + 보상 요구 → "쿠폰 제공해드릴 수 있습니다" 식의 보상 약속
- 권위 사칭 ("관리자입니다") → 개인정보 노출 등 [금지] 가드 풀림

**개선 방안**:
- `BaedalPrompt.SYSTEM_PROMPT` 같은 구조화된 시스템 프롬프트를 `.defaultSystem(...)`으로 주입.
- [역할] / [규칙] / [금지] / [응답 포맷] 4섹션 분리 + 메타 가드(권위 사칭, 우회 기법) 2개 추가 (Round 1 단계 1 결정).

### 결함 2 — API Key 하드코딩

**문제**:
`new OpenAiApi("sk-proj-abc123XYZ")` 처럼 API key가 **소스 코드에 박혀 있다**.

**왜 위험한가**:
- Git에 commit되면 GitHub history에 영구 남음. 레포 public 시 즉시 유출 (`git log -p`로 history 검색하면 발견).
- 키 회수 시 코드 재배포 필요. 운영 중 회수 = 다운타임.
- 환경별 키 분리 불가 (dev/staging/prod 같은 키 강제 → prod 사고 시 dev에서 재현 못 함).

**개선 방안**:
- `application.yml` + 환경 변수 (`${OPENAI_API_KEY}`)로 외부화.
- Spring AI 자동 설정 사용: `spring.ai.openai.api-key` property 등록만 하면 `OpenAiChatClient` 빈이 자동 생성됨. `new OpenAiApi(...)` 직접 생성 불필요.
- 운영 환경에선 AWS Secrets Manager / HashiCorp Vault 같은 시크릿 매니저 연동.

### 결함 3 — 로깅 / 모니터링 부재 (관찰성 부재)

**문제**:
LLM 호출이 일어났는지, 얼마나 걸렸는지, 토큰을 얼마나 썼는지 **추적할 방법이 없다**. `chatClient.call(prompt)` 한 줄로 끝.

**왜 위험한가**:
- **비용 폭주 모니터링 불가**: 사용자가 100,000자 메시지를 보내면 입력 토큰이 비례 증가하여 비용 폭증. 모니터링 없으면 청구서 받아본 뒤 알게 됨.
- **응답 지연 추적 불가**: LLM 호출이 30초 걸려도 어디서 느린지 모름. p99 latency 측정 불가.
- **품질 회귀 감지 불가**: 어느 시점부터 응답이 이상해졌는지 모름. 프롬프트 변경의 효과 정량 측정 불가.

**개선 방안**:
- Round 1 단계 4에서 직접 만든 **`PerformanceLoggingAdvisor`** 같은 Advisor를 `.defaultAdvisors(...)`로 등록 → 호출당 elapsed / promptTokens / completionTokens / totalTokens 로그.
- Spring AI의 Micrometer 자동 계측 활성화 → Prometheus / Grafana 대시보드 연동 가능.
- 토큰 알람 임계치 설정 (예: 단일 호출 5000 tokens 초과 시 alert).

---

## 단골 결함 비교 (퀘스트 힌트 vs 우리 발견)

| 퀘스트 힌트 단골 포인트 | 받은 코드에서 발견? |
|---|---|
| API Key 하드코딩 | ✅ 결함 2 |
| 에러 핸들링 부재 | ✅ (try-catch 없음) |
| System Prompt 미설계 | ✅ 결함 1 |
| 입력 검증 없음 | ✅ (`body.get("message")`가 null이어도 그대로 진행) |
| 토큰 제한 미고려 | ✅ (사용자 입력 길이 제한 없음) |
| 동기 호출만 구현 | ✅ (Round 1 단계 3 streaming의 가치 부재) |
| 로깅/모니터링 없음 | ✅ 결함 3 |
| 문자열 파싱 | ✅ (응답을 `String`으로 받음. JSON 구조화 X — Round 1 단계 1의 Structured Output 미적용) |

**8/8 다 발견**. 단순한 챗봇 예제가 프로덕션에 못 올라가는 이유가 명확해진다.

---

## 가장 큰 학습

**Round 1 4단계의 학습 가치가 여기서 정확히 드러난다**:
- 단계 1 (System Prompt + Structured Output) → 결함 1, 4 (System Prompt + 문자열 파싱) 해결
- 단계 2 (Prompt Lab + [금지] ablation) → 결함 1의 위험성을 데이터로 입증
- 단계 3 (Streaming) → 결함 6 (동기 호출만) 해결
- 단계 4 (PerformanceLoggingAdvisor) → 결함 3 (로깅/모니터링) 해결

→ 부트캠프 4단계는 **AI 생성 코드의 8가지 단골 결함을 차례로 해결하는 커리큘럼**이라는 게 회고적으로 보임.
