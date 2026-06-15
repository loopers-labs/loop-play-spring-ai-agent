# Round 5 작업 이어가기 (Handoff)

> 다른 컴퓨터에서 이 문서만 읽고 Round 5를 이어서 끝낼 수 있도록 정리한 인수인계 문서.
> 최종 업데이트: 2026-06-15

---

## 0. 한 줄 요약

**Round 5 Guardrail 코드(1~4단계)는 전부 구현·커밋 완료.** 남은 건 *앱 기동 후 curl 검증 → PR 문서의 `⏳` 자리 채우기 → 학습기록/AI리뷰 마무리*. 코드는 아직 **로컬에서 `./gradlew test`로 실행 검증되지 않음**(JDK 17 이슈, 아래 §4).

---

## 1. 지금까지 한 일 (커밋 이력)

| 커밋 | 내용 |
|---|---|
| `e315ec6` | `upstream/round5` 머지 — 충돌 5파일은 **round5 버전 채택**, guardrail 패키지/knowledge-extra 유입 |
| `f28ec90` | 1단계 — `InputGuardrailAdvisor.check()` + 두 컨트롤러 Advisor 체인 연결 |
| `66846f6` | 2단계 — `SensitiveDataMasker`(전화/이메일/주소) + `OutputGuardrailAdvisor` 치환 |
| `3c7dbd8` | 3단계 — `HandoffDetector`(EXPLICIT→LEGAL→ANGER) + LLM 호출 전 선검사 |
| `3dfe9a8` | 4단계 — `AssistantController` try/catch Fallback |
| `00e0fdf` | PR 초안 `docs/PR-week5-mission.md` (설계 결정 + 관찰 템플릿) |
| `d5cd8c8` | `@Valid` 복원 + 컨트롤러 테스트 guardrail `@MockBean` 복구 |
| `5e6cb6a` | PR 문서 공통 학습 기록 초안 |

분기점: `git log --oneline e315ec6^..HEAD` 로 전체 확인.

---

## 2. 코드 상태 (구현 완료 — 손댈 필요 없음)

| 파일 | 구현 내용 |
|---|---|
| `guardrail/InputGuardrailAdvisor.java` | `check()`: EMPTY_INPUT / INPUT_TOO_LONG(2000) / PROMPT_INJECTION + 사유별 fallback. order=5, short-circuit |
| `guardrail/SensitiveDataMasker.java` | `maskPhone`(010-****-5678) / `maskEmail`(n***@…) / `maskAddress`([주소 비공개]) |
| `guardrail/OutputGuardrailAdvisor.java` | 빈응답·유출마커·민감정보 3단계 치환. order=50. before/after는 **DEBUG 로그만** |
| `guardrail/HandoffDetector.java` | `detect()`: EXPLICIT→LEGAL→ANGER 우선순위, 메시지에 `1600-0987` |
| `AssistantController.java` | 체인 연결 + Handoff 선검사 + try/catch Fallback + `@Valid` |
| `SupportController.java` | 체인 연결 + Handoff 선검사(SupportResponse 수동 조립) + `@Valid` + `@Slf4j` |

Advisor 체인 실행 순서(getOrder 기준): `input(5) → memory(10) → rag(20) → output(50) → performance(100)`

---

## 3. 이미 내린 설계 결정 (다시 논의하지 말 것)

- **머지 충돌 5파일 = round5 버전 채택** (공식 baseline + guardrail 스캐폴드).
- **`MAX_INPUT_CHARS = 2000`** — 배달 상담 정상 입력은 짧음 → 오탐 없이 토큰/DoS 선제 차단. (스캐폴드 기본값 유지)
- **`@Valid` 복원** — round5 starter가 떼어낸 것을 되살림. `ChatRequest`의 `@NotBlank/@Size(max=1000)`로 엣지 400 fail-fast. CLAUDE.md Bean Validation 규칙 충족.
  - ⚠️ 알려진 중복: `@Size(max=1000)`(HTTP 400) vs 가드레일 `MAX_INPUT_CHARS=2000`(200+안내) 두 길이 상한 공존. assistant 경로는 1000에서 먼저 400. (PR 문서 의문점에 기록됨, Round 6 일원화 후보)
- **마스킹 before/after 로그는 DEBUG 전용** — INFO 이상 금지(평문 민감정보 노출 방지).

---

## 4. ⚠️ 환경 주의점 (다른 컴퓨터에서 먼저 확인)

1. **JDK 17 필요** — 프로젝트 Gradle toolchain이 `languageVersion=17`을 강제. 직전 작업 PC엔 JDK 21/20만 있어 `./gradlew test`/`bootRun`을 **CLI에서 못 돌렸음**. 새 PC에 **JDK 17 설치** 또는 IntelliJ Gradle JVM을 17로 지정할 것. (IntelliJ에서 Run은 JDK 21로도 가능하나, Gradle 빌드는 toolchain 17 필요)
2. **PgVector 기동** — `docker-compose up -d` (pgvector 컨테이너). RAG 시드(`KnowledgeLoader`)가 떠야 시나리오 5(통과) 검증 가능.
3. **Ollama 기동** — `application.yml`의 `spring.ai.ollama.base-url` 확인. 4단계 LLM 실패 검증 시 이 값을 임시로 `http://localhost:1`로 바꿨다가 **반드시 원복**.

---

## 5. 남은 작업 (체크리스트)

### A. 빌드/테스트 초록 확인
- [ ] `./gradlew test` 실행 → `SupportControllerTest`/`AssistantControllerTest` 통과 확인
  - 두 테스트는 guardrail `@MockBean` + `handoffDetector.detect()→none()` 스텁으로 복구해 뒀으나 **실행 검증은 아직**. 실패 시 §6 참고.

### B. 시나리오 curl 검증 → `docs/PR-week5-mission.md`의 `⏳` 자리에 결과 붙이기
- [ ] 1단계 공격/정상 5종 (주입2 / 빈입력 / 5000자 / 정상) + `[InputGuardrail] 차단 — reason=...` 로그 + 1~4 토큰<5 증명
- [ ] 2단계 마스킹 5종 + `2024-1234` 오탐 없음 + `종로3가` 미탐 관찰
- [ ] 3단계 전환 3종 정량표 + 우회("상 담 원"/완곡분노/agent plz) 미탐 캡처
- [ ] 4단계 Tool 실패(`OrderTools`에 throw 1줄→확인→**제거**) / LLM 실패(base-url 변경→확인→**원복**) 2종

curl 예시는 `docs/PR-week5-mission.md` 안에 표로 정리돼 있음. (엔드포인트: `/api/v1/assistant`=String, `/api/v1/support`=SupportResponse)

### C. 문서 마무리
- [ ] PR 제목의 `{본인이름}` 채우기 → `[Round5] {이름} - 4단계까지 완료`
- [ ] 공통 학습 기록(초안 있음)을 검증 결과 반영해 **본인 표현**으로 다듬기 (자가점검: AI 답 복붙 아닐 것)
- [ ] AI 코드 리뷰 3건 작성 (4단계)

### D. 제출
- [ ] 커밋·push 후 GitHub에서 **직접 PR 생성** (제목/본문은 `docs/PR-week5-mission.md` 사용). *PR은 자동 생성하지 않는 것이 이 레포 규칙.*

---

## 6. 테스트가 깨지면 (예상 원인)

- `@WebMvcTest`는 슬라이스라 guardrail 빈을 `@MockBean`으로 줘야 컨텍스트가 뜸. 새 의존성이 컨트롤러에 추가되면 해당 테스트에 `@MockBean` 추가 필요.
- `HandoffDetector`는 빌더 체인 **이전**에 호출되므로 mock이 `null`을 반환하면 NPE. → `when(handoffDetector.detect(anyString())).thenReturn(HandoffDetector.HandoffDecision.none())` 스텁 필수(이미 적용됨).
- `AssistantController`는 `ChatClient`가 아니라 **`ChatClient.Builder`**를 주입받음 → 테스트 Config가 `Builder` mock(RETURNS_SELF)을 제공해야 함(이미 적용됨).

---

## 7. 참고 문서

- 과제 원문: `docs/notion/Round 5_Quest.md`
- PR 본문(작성 중): `docs/PR-week5-mission.md`
- 직전 라운드 PR 형식 참고: `docs/PR-week4-mission.md`