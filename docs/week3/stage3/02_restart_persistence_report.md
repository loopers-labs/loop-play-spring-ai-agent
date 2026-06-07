# 재시작 영속성 검증 — 저장소 3종 비교

**목적** : Chat Memory가 **서버 재시작 후에도 살아남는지**를 저장소 설정별로 비교한다. "JDBC로 바꿨으니 영속된다"는 통념을 실측으로 검증 — 영속을 결정하는 건 JDBC 사용 여부가 아니라 **DB의 저장 모드(mem vs file)** 임을 확인한다.

## 실험 방법

세 설정에 대해 동일 절차를 반복한다(세션 ID `persist-test` 공통).

1. 기동 → `POST /api/v1/assistant`로 1턴 적재(`"2024-1234 어디쯤이야?"`)
2. `GET /session/persist-test/messages`로 적재 확인
3. 앱 종료 → 같은 설정으로 **재기동**
4. 재기동 후 `GET /session/persist-test/messages`(결정적 신호) + `"그거 어디쯤이야?"` 질문(행동 시연)

> 판정: 재기동 후 messages가 남아 있으면 유지. "그거"가 이전 주문(2024-1234)으로 해결되면(=tool 재호출) 유지, 주문번호를 되물으면 소멸.

| 설정 | 기동 방법 |
|---|---|
| InMemory (기본) | `./gradlew bootRun` |
| `jdbc:h2:mem` | `./gradlew bootRun --args='--spring.profiles.active=jdbc'` |
| `jdbc:h2:file` | `./gradlew bootRun --args='--spring.profiles.active=jdbc --spring.datasource.url=jdbc:h2:file:./data/baedal;MODE=PostgreSQL'` |

---

## 결과

### ① InMemory (dev)

- 재시작 전 messages: `USER "2024-1234 어디쯤이야?"` + `ASSISTANT "…역삼역 사거리…"` (2건)
- 재시작 후 messages: `[]`
- "그거" 질문 → `먼저 주문 번호를 알려주시겠어요? …` (tool 미호출)
- → **소멸**. 프로세스 힙(Map)이라 JVM 종료와 함께 사라짐.

### ② jdbc:h2:mem

- 재시작 전 messages: 2건 적재 확인
- 재시작 후 messages: `[]`
- "그거" 질문 → `먼저 주문 번호를 알려주시겠어요? …` (tool 미호출)
- → **소멸**. JDBC를 쓰지만 DB가 인메모리라 JVM 종료 시 DB 자체가 사라짐. (`DB_CLOSE_DELAY=-1`은 JVM 생존 동안만 유효)

### ③ jdbc:h2:file

- 재시작 전 messages: 2건 적재, `./data/baedal.mv.db` 파일 생성 확인
- 재시작 후 messages: **2건 그대로 유지**
- "그거" 질문 → `현재 라이더는 역삼역 사거리 부근에서 …` + 로그 `[Tool] getDeliveryStatus(orderId=2024-1234)`
- → **유지**. 이력이 파일에 남아 재기동 후 복원되고, "그거"가 직전 주문(2024-1234)으로 해결됨.

### 정리 표

| 저장소 설정 | 재시작 후 Memory 유지? | 비고 |
|---|---|---|
| InMemory (기본) | ❌ 아니오 | 프로세스 힙 → JVM 종료 시 소멸 |
| `jdbc:h2:mem:...` | ❌ 아니오 | JVM 생존 동안만 유지, 재시작 시 DB 소멸 |
| `jdbc:h2:file:...` | ✅ 예 | 파일(`./data/baedal.mv.db`) 기반 영속 |

---

## 결론

- **JDBC ≠ 영속**: JDBC를 써도 `h2:mem`이면 InMemory와 똑같이 재시작 시 사라진다. 영속을 결정하는 건 **DB의 저장 위치(메모리 vs 디스크)** 다.
- 진짜 영속은 **파일 모드 H2**나 **외부 DB(PostgreSQL 등)** 라야 한다. 재배포·장애 복구·멀티 인스턴스에서 세션을 유지하려면 후자가 필요하다.
- 실험 중 드러난 두 이슈는 **모두 해결**됐다 — 발견 ①(프로필별 `autoconfigure.exclude`로 dev 기동 정상화), 발견 ②(`initialize-schema: always`로 mem·file 모두 스키마 생성). 이제 dev는 순수 InMemory로, jdbc는 mem/file 어느 쪽으로든 추가 플래그 없이 동작한다.

---

## 각 모드 실행 방법

**기동 방법 표의 명령 그대로면 되고, yml에 추가할 것은 없다.** 모드별로 이미 갖춰져 있기 때문이다.

| 모드 | 명령 | yml 추가 불필요한 이유 |
|---|---|---|
| InMemory | `./gradlew bootRun` | `application.yml`이 dev 프로필 기본 + jdbc 자동구성 `exclude`까지 설정해 둠 |
| `jdbc:h2:mem` | `./gradlew bootRun --args='--spring.profiles.active=jdbc'` | `application-jdbc.yml`에 mem URL·`exclude:[]`·`platform`·`initialize-schema:always`가 이미 있음 |
| `jdbc:h2:file` | `./gradlew bootRun --args='--spring.profiles.active=jdbc --spring.datasource.url=jdbc:h2:file:./data/baedal;MODE=PostgreSQL'` | URL을 **커맨드라인 인자로 덮어씀** → yml보다 우선순위가 높아 그 실행만 file로 전환 |

- InMemory·mem은 **프로필 기본값**이라 명령만으로 끝난다.
- file은 **`--spring.datasource.url`로 런타임 오버라이드**하는 방식이라 yml을 안 건드려도 그 한 번의 실행만 file로 뜬다. (Spring 속성 우선순위: 커맨드라인 > `application-jdbc.yml`)
- **예외**: file을 인자 없이 **영구 기본**으로 쓰려면 `application-jdbc.yml`의 `datasource.url`을 file로 바꾸거나 별도 프로필(예: `jdbc-file`)을 만든다. 일회성 확인이면 위 명령으로 충분하다.


