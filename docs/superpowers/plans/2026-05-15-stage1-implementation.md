# Stage 1: 기본 API + System Prompt + Structured Output 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/v1/support`가 `BaedalPrompt.SYSTEM_PROMPT`를 적용하고 시나리오별로 다른 `SupportResponse` JSON을 반환하게 한다.

**Architecture:** `SupportController`는 `ChatClient.Builder`에 시스템 프롬프트를 주입하고 `.entity(SupportResponse.class)`로 구조화된 응답을 받는다. Spring AI가 JSON 스키마를 자동 생성해 프롬프트에 주입하고 응답을 파싱한다.

**Tech Stack:** Spring Boot 3.4.1, Spring AI 1.0.0, Ollama (qwen2.5), Lombok, JUnit 5, Mockito

---

## 파일 맵

| 파일 | 변경 유형 | 역할 |
|------|-----------|------|
| `src/main/java/com/baedal/support/SupportResponse.java` | Modify | `estimatedResolutionMinutes` 추가, `COMPLAINT` enum 추가 |
| `src/main/java/com/baedal/support/BaedalPrompt.java` | Modify | [금지]에 의료·법률 조언 금지 규칙 추가 |
| `src/main/java/com/baedal/support/SupportController.java` | Modify | TODO 구현 |
| `src/test/java/com/baedal/support/SupportResponseTest.java` | Create | `SupportResponse` 직렬화/역직렬화 단위 테스트 |
| `src/test/java/com/baedal/support/BaedalPromptTest.java` | Create | `SYSTEM_PROMPT` 필수 섹션 포함 여부 단위 테스트 |
| `src/test/java/com/baedal/support/SupportControllerTest.java` | Create | `ChatClient.Builder` mock으로 컨트롤러 단위 테스트 |
| `README.md` | Modify | 3가지 시나리오 응답 JSON + 설계 결정 문서 추가 |

---

## Task 1: SupportResponse — 필드 추가 + COMPLAINT enum

**Files:**
- Modify: `src/main/java/com/baedal/support/SupportResponse.java`
- Create: `src/test/java/com/baedal/support/SupportResponseTest.java`

- [ ] **Step 1: 테스트 디렉터리 생성**

```bash
mkdir -p src/test/java/com/baedal/support
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/baedal/support/SupportResponseTest.java`:

```java
package com.baedal.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SupportResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_include_estimatedResolutionMinutes_field() throws Exception {
        var response = new SupportResponse(
            "테스트 요약",
            SupportResponse.Category.DELIVERY,
            SupportResponse.Urgency.NORMAL,
            "배달 현황을 확인하세요.",
            List.of("주문번호"),
            10
        );

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("estimatedResolutionMinutes");
        assertThat(response.estimatedResolutionMinutes()).isEqualTo(10);
    }

    @Test
    void should_have_complaint_category() {
        assertThat(SupportResponse.Category.valueOf("COMPLAINT"))
            .isEqualTo(SupportResponse.Category.COMPLAINT);
    }

    @Test
    void should_deserialize_from_json() throws Exception {
        String json = """
            {
              "summary": "라이더 사고 접수",
              "category": "COMPLAINT",
              "urgency": "HIGH",
              "nextAction": "보상팀에 전달합니다.",
              "neededInfo": ["주문번호", "사진"],
              "estimatedResolutionMinutes": 2880
            }
            """;

        SupportResponse response = mapper.readValue(json, SupportResponse.class);

        assertThat(response.category()).isEqualTo(SupportResponse.Category.COMPLAINT);
        assertThat(response.estimatedResolutionMinutes()).isEqualTo(2880);
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

```bash
./gradlew test --tests "com.baedal.support.SupportResponseTest" 2>&1 | tail -20
```

예상: FAIL — `SupportResponse`에 `estimatedResolutionMinutes` 파라미터 없음, `COMPLAINT` enum 없음

- [ ] **Step 4: SupportResponse 수정**

`src/main/java/com/baedal/support/SupportResponse.java` 전체를 아래로 교체:

```java
package com.baedal.support;

import java.util.List;

public record SupportResponse(
        String summary,
        Category category,
        Urgency urgency,
        String nextAction,
        List<String> neededInfo,
        Integer estimatedResolutionMinutes
) {
    public enum Category { ORDER, DELIVERY, REFUND, PAYMENT, COMPLAINT, ETC }
    public enum Urgency  { LOW, NORMAL, HIGH, CRITICAL }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
./gradlew test --tests "com.baedal.support.SupportResponseTest" 2>&1 | tail -20
```

예상: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/baedal/support/SupportResponse.java \
        src/test/java/com/baedal/support/SupportResponseTest.java
git commit -m "feat: add estimatedResolutionMinutes field and COMPLAINT category to SupportResponse"
```

---

## Task 2: BaedalPrompt — 의료·법률 조언 금지 규칙 추가

**Files:**
- Modify: `src/main/java/com/baedal/support/BaedalPrompt.java`
- Create: `src/test/java/com/baedal/support/BaedalPromptTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/baedal/support/BaedalPromptTest.java`:

```java
package com.baedal.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BaedalPromptTest {

    @Test
    void system_prompt_should_contain_required_sections() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("[역할]")
            .contains("[규칙]")
            .contains("[금지]")
            .contains("[응답 포맷]");
    }

    @Test
    void system_prompt_should_prohibit_competitor_recommendations() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("타 배달 플랫폼");
    }

    @Test
    void system_prompt_should_prohibit_personal_info_exposure() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("개인정보");
    }

    @Test
    void system_prompt_should_prohibit_coupon_promises() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("쿠폰");
    }

    @Test
    void system_prompt_should_prohibit_medical_advice() {
        assertThat(BaedalPrompt.SYSTEM_PROMPT)
            .contains("의료");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew test --tests "com.baedal.support.BaedalPromptTest" 2>&1 | tail -20
```

예상: FAIL — `system_prompt_should_prohibit_medical_advice` 실패

- [ ] **Step 3: BaedalPrompt [금지] 섹션에 규칙 추가**

`src/main/java/com/baedal/support/BaedalPrompt.java`의 `[금지]` 섹션에 한 줄 추가:

```java
            [금지]
            - 타 배달 플랫폼(쿠팡이츠, 요기요 등)을 추천하거나 비교하지 않습니다.
            - 사장님/라이더의 개인정보(연락처, 주소 등)를 절대 노출하지 않습니다.
            - 고객이 요구하더라도 쿠폰, 할인, 보상 지급을 약속하지 않습니다.
            - 건강 피해(알레르기, 식중독 등)에 대해 의료적 판단을 하지 않으며, 즉시 의료기관 방문을 안내합니다.
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew test --tests "com.baedal.support.BaedalPromptTest" 2>&1 | tail -20
```

예상: BUILD SUCCESSFUL, 5 tests passed

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/baedal/support/BaedalPrompt.java \
        src/test/java/com/baedal/support/BaedalPromptTest.java
git commit -m "feat: add medical/legal advice prohibition to BaedalPrompt system prompt"
```

---

## Task 3: SupportController — TODO 구현

**Files:**
- Modify: `src/main/java/com/baedal/support/SupportController.java`
- Create: `src/test/java/com/baedal/support/SupportControllerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/baedal/support/SupportControllerTest.java`:

```java
package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SupportController.class)
class SupportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatClient.Builder builder;

    private SupportResponse deliveryResponse;

    @BeforeEach
    void setUp() {
        deliveryResponse = new SupportResponse(
            "배달 현황을 조회하겠습니다.",
            SupportResponse.Category.DELIVERY,
            SupportResponse.Urgency.NORMAL,
            "주문 현황 페이지를 확인하세요.",
            List.of("주문번호"),
            10
        );

        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(SupportResponse.class)).thenReturn(deliveryResponse);
    }

    @Test
    void triage_returns_200_with_support_response() throws Exception {
        mockMvc.perform(post("/api/v1/support")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"주문번호 2024-1234 배달 어디쯤에 있어요?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("DELIVERY"))
            .andExpect(jsonPath("$.urgency").value("NORMAL"))
            .andExpect(jsonPath("$.estimatedResolutionMinutes").value(10));
    }

    @Test
    void triage_uses_system_prompt() throws Exception {
        mockMvc.perform(post("/api/v1/support")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"테스트\"}"))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(builder).defaultSystem(BaedalPrompt.SYSTEM_PROMPT);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew test --tests "com.baedal.support.SupportControllerTest" 2>&1 | tail -20
```

예상: FAIL — `UnsupportedOperationException: TODO: 구현하세요`

- [ ] **Step 3: SupportController 구현**

`src/main/java/com/baedal/support/SupportController.java`의 `triage` 메서드를 아래로 교체:

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

- [ ] **Step 4: 전체 테스트 실행 — 통과 확인**

```bash
./gradlew test 2>&1 | tail -20
```

예상: BUILD SUCCESSFUL, 10 tests passed

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/baedal/support/SupportController.java \
        src/test/java/com/baedal/support/SupportControllerTest.java
git commit -m "feat: implement SupportController with system prompt and structured output"
```

---

## Task 4: 시나리오 검증 + README 문서화

**Files:**
- Modify: `README.md`

> Ollama가 로컬에서 실행 중이어야 합니다 (`ollama serve` + `ollama pull qwen2.5`).

- [ ] **Step 1: 앱 실행**

```bash
./gradlew bootRun
```

새 터미널에서 아래 curl 명령 실행.

- [ ] **Step 2: 시나리오 1 — 배달 위치 문의**

```bash
curl -s -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message": "주문번호 2024-1234 배달 어디쯤에 있어요?"}' | jq .
```

응답 JSON을 복사해둔다.

- [ ] **Step 3: 시나리오 2 — 취소·환불 문의**

```bash
curl -s -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message": "방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"}' | jq .
```

응답 JSON을 복사해둔다.

- [ ] **Step 4: 시나리오 3 — 라이더 사고 보상**

```bash
curl -s -X POST http://localhost:8080/api/v1/support \
  -H "Content-Type: application/json" \
  -d '{"message": "라이더가 음식을 엎었다는데 보상 받을 수 있나요?"}' | jq .
```

응답 JSON을 복사해둔다.

- [ ] **Step 5: README에 결과 + 설계 결정 문서 추가**

`README.md` 하단에 아래 섹션을 추가하고 각 JSON 블록을 실제 응답으로 채운다:

````markdown
## 1단계: 기본 API + System Prompt + Structured Output

### 시나리오별 응답

**시나리오 1: 배달 위치 문의**
```
POST /api/v1/support
{"message": "주문번호 2024-1234 배달 어디쯤에 있어요?"}
```
```json
<시나리오 1 실제 응답 붙여넣기>
```

**시나리오 2: 주문 취소·환불 문의**
```
POST /api/v1/support
{"message": "방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"}
```
```json
<시나리오 2 실제 응답 붙여넣기>
```

**시나리오 3: 라이더 사고 보상**
```
POST /api/v1/support
{"message": "라이더가 음식을 엎었다는데 보상 받을 수 있나요?"}
```
```json
<시나리오 3 실제 응답 붙여넣기>
```

---

### 설계 결정 문서

#### [금지] 규칙 3+1가지를 선택한 이유

| 규칙 | 근거 | 빼면 생기는 사고 |
|------|------|----------------|
| 타사 플랫폼 추천 금지 | 경쟁사 유도 방지, 브랜드 신뢰 보호 | "쿠팡이츠가 더 빠르지 않아요?"에 AI가 동의 |
| 개인정보 노출 금지 | 개인정보보호법 의무 | 고객 요청 시 라이더 연락처 노출 |
| 쿠폰/보상 약속 금지 | 무권한 약속 방지, 법적 리스크 제거 | "5000원 쿠폰 드릴게요"가 법적 구속력 발생 |
| 의료적 판단 금지 (추가) | 알레르기·식중독 판단은 의료 행위 | "증상이 경미하니 괜찮을 것 같습니다" → 의료 과실 리스크 |

3가지 원래 규칙은 모두 "빼면 실제 운영에서 법적·비즈니스 사고가 나는 규칙"이다. 의료 규칙은 배달 상담에서 음식 알레르기 반응이나 식중독 의심 사례에서 현실적으로 필요하다.

#### Category enum — 왜 이 6개인가

| Category | 해당 문의 유형 |
|----------|-------------|
| ORDER | 주문 접수, 메뉴 오류, 주문 변경 |
| DELIVERY | 배달 현황, 지연, 위치 확인 |
| REFUND | 환불 요청, 취소 후 처리 |
| PAYMENT | 결제 오류, 이중 청구 |
| COMPLAINT | 음식 파손, 오배달, 라이더 사고, 품질 불만 |
| ETC | 위 카테고리에 속하지 않는 문의 |

원래 5개(COMPLAINT 없음)에서 시나리오 3 "라이더가 음식을 엎었다"를 분류할 때 REFUND나 ETC에 억지로 넣어야 했다. COMPLAINT를 추가해 피해 경험 불만을 독립 카테고리로 분리했다.

#### 추가 필드 `estimatedResolutionMinutes` 선택 근거

고객이 가장 자주 묻는 "얼마나 걸려요?"를 구조화된 숫자로 뽑아낸다. `urgency`는 우선순위만 나타내고 시간 정보는 없다. 숫자 필드로 만들면 프론트엔드에서 "약 N분 소요 예정" 형태로 바로 렌더링 가능하고, LLM이 urgency + category를 종합해 추론하도록 유도한다.

예상 기준값:
- 배달 위치 확인: 5~10분
- 주문 취소: 10~30분  
- 환불: 1440~4320분 (1~3일)
- 라이더 사고 보상: 2880분 이상 또는 null (조사 필요)
````

- [ ] **Step 6: 커밋**

```bash
git add README.md
git commit -m "docs: add stage1 scenario results and design decisions to README"
```
