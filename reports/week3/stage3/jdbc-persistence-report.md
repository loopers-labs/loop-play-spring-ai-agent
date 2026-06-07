# 3단계 — InMemory vs JDBC 영속화 + 재시작 실험 (평가축 ★)

> Round 3 평가축 중 하나: **InMemory vs JDBC 의사결정 트리.**
> 저장소를 바꿔 *서버 재시작 시 대화가 유지되는가* 를 직접 실험하고, 선택 기준을 정리한다.

## JDBC 전환 — 함정 5종 연쇄

starter(`JdbcChatMemoryExample`)와 강의 노트는 *"의존성 추가 + `@Profile("!jdbc")` + `h2:mem`"* 이면
된다고 했지만, **그대로 하면 5번 막힌다.** 각 함정을 풀 때마다 다음 함정이 드러났다.

| # | 증상 | 원인 | 해결 |
|---|---|---|---|
| 1 | 기본 프로필 부팅 시 `expected single bean but found 2` | h2 가 classpath 에 있으면 기본 프로필에서도 `DataSourceAutoConfiguration` + `JdbcChatMemoryRepositoryAutoConfiguration` 이 발동해 우리 InMemory 빈과 충돌. **`@Profile("!jdbc")` 만으로는 부족** | application.yml 에서 두 자동구성 `exclude` |
| 2 | jdbc 프로필에서 `ChatMemoryRepository` 0개 | base 의 `exclude` 가 jdbc 프로필에도 상속됨 | application-jdbc.yml 에서 `autoconfigure.exclude: []` 로 override |
| 3 | `No schema scripts found ... schema-h2.sql` | **Spring AI 1.0.0 jdbc jar 에 `schema-h2.sql` 이 없다** (postgresql/sqlserver/hsqldb/mariadb 만 제공) | `platform: postgresql` 로 강제 — DDL 이 H2(MODE=PostgreSQL)와 호환 |
| 4 | (잠재) 재시작 시 데이터 소실 | `jdbc:h2:**mem**` 은 JVM 재시작 시 DB 도 사라져 *영속성 실험 자체가 불가* | `jdbc:h2:**file**:./data/baedal` 로 전환 |
| 5 | 재시작 후 `Table SPRING_AI_CHAT_MEMORY not found (database is empty)` | `initialize-schema: **embedded**` 는 *in-memory* DB 일 때만 init — **file H2 는 Spring 의 embedded 판정에 안 들어가** schema 생성이 스킵됨 | `initialize-schema: **always**` (CREATE TABLE IF NOT EXISTS 라 재시작 안전) |

**가장 교훈적인 건 4↔5 의 연쇄**다: 재시작 실험을 하려고 `mem→file`(함정4)로 고치자, 이번엔 file 이
embedded 가 아니라서 테이블이 안 만들어지는(함정5) 새 문제가 터졌다. *"한 설정을 고치면 그 설정에
딸린 다른 가정이 깨진다"* 를 그대로 보여준다.

> 강의 노트의 `h2:mem + initialize-schema: embedded` 조합은 **단발 데모는 되지만 재시작 실험은 구조적으로
> 불가능한 설정**이었다. 평가축이 "재시작 실험"인데 제시된 설정으로는 그 실험을 할 수 없는 셈 —
> 직접 부딪혀야 보이는 함정이다.

## 최종 설정 (정리)

```yaml
# application.yml (기본 = InMemory)
spring:
  autoconfigure:
    exclude:               # h2 classpath 로 인한 자동구성 충돌 차단
      - ...jdbc.DataSourceAutoConfiguration
      - ...JdbcChatMemoryRepositoryAutoConfiguration

# application-jdbc.yml (jdbc 프로필)
spring:
  autoconfigure:
    exclude: []            # 자동구성 다시 켬
  datasource:
    url: jdbc:h2:file:./data/baedal;MODE=PostgreSQL   # file = 영속
  ai:
    chat.memory.repository.jdbc:
      initialize-schema: always      # file 은 embedded 아님 → always
      platform: postgresql           # schema-h2.sql 부재 → postgresql DDL 사용
```
`ChatMemoryConfig.chatMemoryRepository()` 에 `@Profile("!jdbc")` — jdbc 프로필에선 자동구성 repo 사용.

## 재시작 실험 (필수)

동일 2턴 대화(`"2024-1234 배달 어디?"` → `"그거 취소 가능?"`)를 두 저장소에서 수행 후 **서버 재시작**.

| 저장소 | 재시작 전 | 재시작 후 | 결과 |
|---|---:|---:|---|
| **JDBC (`h2:file`)** | 4건 | **4건** | ✅ 디스크(`./data/baedal.mv.db`)에 유지 |
| **InMemory** | 4건 | **0건** | ❌ JVM 종료와 함께 소실 |

JDBC 재시작 후 조회(LLM 호출 없이 `GET /session/restart-jdbc/messages`):
```
USER      : 2024-1234 배달 어디쯤이에요?
ASSISTANT : 주문 2024-1234는 현재 배달 중이며 라이더가 역삼역 사거리 부근에 있습...
USER      : 그거 취소 가능한가요?
ASSISTANT : 주문 2024-1234는 현재 배달 중이라 취소가 어렵습니다. 매장과 직접 확인해...
```
→ **재시작을 넘어 대화 맥락("그거"→1234)까지 그대로 복원**된다. InMemory 였다면 배포 한 번에 전부 증발.

## 테이블 스키마 (schema-postgresql.sql, H2 적용)

```sql
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER','ASSISTANT','SYSTEM','TOOL')),
    "timestamp" TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS ..._CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");
```
- `GET /session/{id}/messages` 가 사실상 `SELECT content, type FROM SPRING_AI_CHAT_MEMORY WHERE
  conversation_id=? ORDER BY "timestamp"` 다 (BadSqlGrammar 로그로 실제 쿼리 확인).
- **TOOL 메시지는 저장되지 않는다** — `MessageChatMemoryAdvisor` 가 USER/ASSISTANT 만 적재 (1단계 관찰과 일치).
  그래서 "그거"→orderId 재해석은 ASSISTANT 응답 본문에 orderId 가 포함돼야 가능하다.

## H2 Console 쿼리 결과

`/h2-console` 접속 (JDBC URL `jdbc:h2:file:./data/baedal;MODE=PostgreSQL`, user `sa`, pw 없음) 후
`SELECT * FROM SPRING_AI_CHAT_MEMORY ORDER BY conversation_id, "timestamp"`:

| conversation_id | content | type | timestamp |
|---|---|---|---|
| restart-jdbc | 2024-1234 배달 어디쯤이에요? | USER | 2026-05-31 12:22:29.124 |
| restart-jdbc | 주문 2024-1234는 현재 배달 중이며 라이der가 역삼역 사거리 부근에 있습니다. 약 15분 내 도착 예정이에요. | ASSISTANT | …29.125 |
| restart-jdbc | 그거 취소 가능한가요? | USER | …29.126 |
| restart-jdbc | 주문 2024-1234는 현재 배달 중이라 취소가 어렵습니다. 매장과 직접 확인해 보시는 것이 좋을 것 같습니다. … | ASSISTANT | …29.127 |

→ `/session/{id}/messages`(type+content)에 안 보이던 **`conversation_id`·`timestamp` 컬럼까지 직접 확인**:

- **`timestamp` 밀리초 순차** (124→125→126→127, 1ms 간격) — *재시작 후에도 이 4건이 그대로 보존* = file 영속의 직접 증거 (재시작 실험과 동일 데이터).
- **`type` 은 USER/ASSISTANT 만** — schema CHECK 엔 `SYSTEM`/`TOOL` 도 허용되지만 실제 적재는 둘뿐 (위 관찰과 일치).
- **`conversation_id` = restart-jdbc 단일** — 세션 단위로 분리 저장됨 (다른 세션이면 다른 conversation_id 행).

## 의사결정 트리 — InMemory vs JDBC

```
Q1. 서버 재시작/배포 시 대화가 사라져도 되는가?
    YES → InMemory 로 충분 (설정 0, 빠름)
    NO  → Q2
Q2. 멀티 인스턴스(로드밸런서 뒤 2대+)로 배포되는가?
    YES → JDBC/Redis 필수 (인스턴스 간 세션 공유)
    NO  → Q3
Q3. 상담 이력을 감사/법적 사유로 N년 보관해야 하는가?
    YES → JDBC (+ 보존정책)
    NO  → InMemory + TTL/주기삭제로도 가능
```

우리 실험이 이 트리의 **Q1 분기를 데이터로 증명**했다: InMemory 는 재시작에 0건, JDBC 는 유지.
배달 상담은 *"앱 껐다 켜도 그 주문 대화가 이어지길"* 기대하므로 실서비스라면 Q1=NO → 최소 JDBC.
단 학습/데모 단계(단일 인스턴스, 단기 세션)에선 InMemory 가 정답 — **전제가 깨지는 순간 손실이 즉시 발생**한다.

## 개인정보 — 영속화의 대가

JDBC 로 가는 순간 대화가 디스크에 평문으로 남는다. `SPRING_AI_CHAT_MEMORY.content` 에는 고객이
자발적으로 말한 전화번호·주소가 그대로 쌓인다.

| 리스크 | 우리 현재 상태 | 대응 |
|---|---|---|
| 평문 저장 | **평문 (위반)** | 저장 전 마스킹 (Round 5 Guardrail) |
| 무기한 보존 | **TTL 없음 (위반)** | 주기 배치 삭제 (예: 90일) |
| 접근 제어 | sa 계정 풀권한 | 상담원은 read-only 뷰만 |
| 암호화 | 평문 mv.db | 컬럼 암호화 / TDE |

→ **Memory 를 영속화하는 결정은 곧 개인정보 처리자가 되는 결정**이다. InMemory→JDBC 는 기술 선택을
넘어 법무/보안 영역으로 넘어간다.

## 결론

- **재시작 실험으로 평가축 충족**: JDBC 유지 / InMemory 소실을 4건↔0건으로 직접 확인.
- **함정 5종**: 강의·starter 설정으론 재시작 실험 자체가 불가능했고, 5개를 차례로 풀어야 동작했다.
  특히 `mem→file→always` 의 연쇄가 핵심.
- **선택 기준**: 재시작 생존·멀티 인스턴스·감사 중 하나라도 필요하면 JDBC. 그 순간 개인정보 책임이 따라온다.

## 학습 기록

→ Round 3 공통 학습 기록(내가 배운 것 / 의문점 / Round 4 아이디어)은 [README](../../../README.md) 의 *Round 3 — 공통 학습 기록* 참조.
