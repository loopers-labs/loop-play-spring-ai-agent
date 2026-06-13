# Round 3 · 3단계 — InMemory vs JdbcChatMemory

> `jdbc` 프로필에서 Spring AI의 `JdbcChatMemoryRepository`(자동 구성)로 전환.
> `ChatMemoryConfig.chatMemoryRepository()`는 `@Profile("!jdbc")`로 한정 → jdbc 프로필에선 자동 구성 빈이 주입.
> 실행: `./gradlew bootRun --args='--spring.profiles.active=jdbc'`

## 🐛 벤더 통합 함정 — Spring AI 1.0.0은 H2 스키마를 번들하지 않음

처음 jdbc 프로필로 기동 시 startup 실패:
```
java.lang.IllegalStateException: No schema scripts found at location
'classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-h2.sql'
```
Spring AI 1.0.0의 `spring-ai-starter-model-chat-memory-repository-jdbc`는 postgresql/mysql/mariadb/hsqldb/sqlserver 스키마만 jar에 담고 **H2용 `schema-h2.sql`은 없다**. `initialize-schema`가 platform(`h2`)에 맞는 스크립트를 못 찾아 빈 생성 실패.

**해결**: H2(`MODE=PostgreSQL`)용 스키마를 직접 작성하고 경로 지정.
- `src/main/resources/db/schema-h2.sql` 생성 (`CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY ...`, 파일 DB 재시작 시 재실행 대비 `IF NOT EXISTS`).
- `application-jdbc.yml`:
  ```yaml
  spring.ai.chat.memory.repository.jdbc:
    initialize-schema: always
    schema: classpath:db/schema-h2.sql
  ```
→ 문서/단위테스트로는 안 잡히고 **실제 기동에서만** 드러나는 종류의 벤더 통합 이슈.

## 검증 — jdbc 프로필에서 1단계 시나리오 재현

`X-Session-Id: jdbc-A`로 2턴 대화 후 `GET /api/v1/session/jdbc-A/messages`:
```json
[{"type":"USER","content":"주문번호 2024-1234 배달 어디쯤이에요?"},
 {"type":"ASSISTANT","content":"...역삼역 사거리 부근..."},
 {"type":"USER","content":"그거 언제 도착해요?"},
 {"type":"ASSISTANT","content":"...도착 예정..."}]
```
`GET /session/ids` → `["jdbc-A"]`. InMemory와 **동일하게 동작** (저장소만 교체, ChatMemory/Advisor 레이어는 그대로).

## 재시작 실험 (영속성 검증)

| URL | 재시작 후 `GET /session/{id}/messages` | "그거" 질문 |
|-----|------|------|
| `jdbc:h2:mem:baedal` | `[]` (소멸) | 맥락 없음 |
| `jdbc:h2:file:./data/baedal` | 4건 **유지** | "오후 8시 9분경"(재시작 전 ETA) 재사용 |

- **h2:mem**: 앱 종료(JVM exit) 시 인메모리 DB가 사라져 `DB_CLOSE_DELAY=-1`이어도 재기동 후 `[]`, `ids=[]`.
- **h2:file**: `data/baedal.mv.db`(25KB) 파일에 보존 → 재기동 후에도 메시지 4건 + `ids=["jdbc-file-A"]` 그대로. 이어서 "그거 언제 도착?"이 **재시작 전 ETA를 정확히 회수**.

→ 저장소 추상화(`ChatMemoryRepository`) 덕에 코드 변경 없이 datasource URL만 바꿔 영속성 on/off. (InMemory↔JDBC 전환은 빈 한정(`@Profile`)만으로.)

---

## 설계 결정 문서 — 의사결정 트리 (InMemory vs JDBC)

> *초안, 제출 전 검토*

| 질문 | YES → 선택 | 근거 |
|------|-----------|------|
| 서버 재시작 시 대화가 사라져도 되나? | YES → **InMemory** / NO → JDBC | 배포(재시작)마다 진행 중 상담 유실 여부 |
| 멀티 인스턴스(로드밸런싱)인가? | YES → **JDBC**(공유 저장소) | InMemory는 인스턴스별 분리 → 다른 서버로 붙으면 맥락 끊김 |
| 상담 이력을 감사(audit)/분쟁 대응에 남겨야 하나? | YES → **JDBC** | InMemory는 휘발 → 감사 불가 |
| 개발/데모/짧은 단일 세션 용도인가? | YES → **InMemory** | JDBC는 오버엔지니어링(스키마·DB 운영 비용) |
| 개인정보(전화/주소)가 대화에 평문 축적되나? | (둘 다 주의) | JDBC면 보존기간·마스킹·삭제권 정책 필수, InMemory는 휘발이 오히려 안전 |

**경계 한 줄**: *단일 인스턴스 + 세션 내 종료되는 거래형 상담*이면 InMemory로 충분. **멀티 인스턴스이거나 세션을 넘는 영속/감사가 필요한 순간 JDBC**. 배달 상담의 1차 응대는 전자에 가깝지만, "어제 환불 건 재문의"처럼 세션 수명을 넘기면 JDBC가 필요해진다.

**운영 고려(JDBC)**: `conversation_id` 인덱스 필수(스키마 포함), 오래된 세션 TTL/아카이브 배치, 개인정보 마스킹(5주차 Guardrail과 연계), 멀티 인스턴스면 PostgreSQL 등 공유 DB.
