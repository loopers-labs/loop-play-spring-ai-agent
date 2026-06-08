# 단계 3 JDBC Memory 저장소 실험

실행 시각: 2026-06-01 19:42~20:03 KST

## 1. 첫 실패: H2 schema script 없음

처음 설정:

```yaml
spring:
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: embedded
```

실행:

```bash
./gradlew bootRun --args='--spring.profiles.active=jdbc'
```

실패:

```text
No schema scripts found at location
'classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-h2.sql'
```

jar 내부 확인:

```text
schema-postgresql.sql
schema-sqlserver.sql
schema-hsqldb.sql
schema-mariadb.sql
```

Spring AI 1.0.0의 JDBC memory repository jar에는 `schema-h2.sql`이 없었다. H2를 `MODE=PostgreSQL`로 띄우고 `platform: postgresql`을 명시하니 기동됐다.

## 2. jdbc:h2:mem

설정:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:baedal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always
            platform: postgresql
```

기동 로그:

```text
HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:baedal user=SA
H2 console available at '/h2-console'. Database available at 'jdbc:h2:mem:baedal'
```

요청:

```bash
curl -s -X POST http://localhost:8080/api/v1/assistant \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: r3-jdbc-mem" \
  -d '{"message":"2024-1234 어디쯤 있어요?"}'
```

Memory 확인:

```json
[
  {"type":"user","text":"2024-1234 어디쯤 있어요?"},
  {"type":"assistant","text":"현재 배달원은 역삼역 사거리 부근에서 배송 중입니다. 예상 도착 시간은 2026년 6월 1일 오후 7시 58분 42초 입니다."}
]
```

재시작 후:

```bash
curl -s http://localhost:8080/api/v1/session/r3-jdbc-mem/messages
curl -s http://localhost:8080/api/v1/session/ids
```

```json
[]
[]
```

`jdbc:h2:mem`도 JVM 재시작 후에는 유지되지 않았다.

## 3. jdbc:h2:file

실행:

```bash
./gradlew bootRun --args='--spring.profiles.active=jdbc --spring.datasource.url=jdbc:h2:file:./data/baedal-r3;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'
```

기동 로그:

```text
HikariPool-1 - Added connection conn0: url=jdbc:h2:file:./data/baedal-r3 user=SA
H2 console available at '/h2-console'. Database available at 'jdbc:h2:file:./data/baedal-r3'
```

요청 후 Memory:

```json
[
  {"type":"user","text":"2024-1234 어디쯤 있어요?"},
  {"type":"assistant","text":"현재 배달원은 역삼역 사거리 부근에서 작업 중이며, 예상 도착 시간은 2026년 6월 1일 오후 8시 17분입니다. 추가로 궁금한 점이 있으시면 알려주세요."}
]
```

앱 종료 후 같은 file URL로 재기동한 뒤:

```json
[
  {"type":"user","text":"2024-1234 어디쯤 있어요?"},
  {"type":"assistant","text":"현재 배달원은 역삼역 사거리 부근에서 작업 중이며, 예상 도착 시간은 2026년 6월 1일 오후 8시 17분입니다. 추가로 궁금한 점이 있으시면 알려주세요."}
]
```

세션 목록:

```json
["r3-jdbc-file"]
```

H2 Shell query:

```bash
java -cp /Users/hyungki/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.3.232/4fcc05d966ccdb2812ae8b9a718f69226c0cf4e2/h2-2.3.232.jar \
  org.h2.tools.Shell \
  -url 'jdbc:h2:file:./data/baedal-r3;MODE=PostgreSQL' \
  -user sa \
  -password '' \
  -sql 'SELECT conversation_id, type, content FROM SPRING_AI_CHAT_MEMORY ORDER BY "timestamp";'
```

```text
CONVERSATION_ID | TYPE      | CONTENT
r3-jdbc-file    | USER      | 2024-1234 어디쯤 있어요?
r3-jdbc-file    | ASSISTANT | 현재 배달원은 역삼역 사거리 부근에서 작업 중이며, 예상 도착 시간은 2026년 6월 1일 오후 8시 17분입니다. 추가로 궁금한 점이 있으시면 알려주세요.
(2 rows, 4 ms)
```
