package com.baedal.support.memory;

/**
 * 3주차 — JDBC Chat Memory 대안 구현 예시 (문서용).
 *
 * <p>이 클래스는 <b>실제로 동작하지 않는 문서/참고용 코드</b>다.
 * 기본 프로젝트는 InMemory Repository로 동작하며, 영속화가 필요한 경우에 대한
 * "어떻게 바꾸면 되는가"를 한 파일에 모아놓은 것이다.
 *
 * <h3>언제 JDBC로 가야 하는가</h3>
 * <table>
 *   <caption>InMemory vs JDBC 선택 기준</caption>
 *   <tr><th>질문</th><th>InMemory</th><th>JDBC</th></tr>
 *   <tr><td>서버 재시작 시 대화가 사라져도 되는가?</td><td>O</td><td>X</td></tr>
 *   <tr><td>멀티 인스턴스 환경에서 어느 서버로 붙어도 동일한 대화여야 하는가?</td><td>X</td><td>O</td></tr>
 *   <tr><td>고객 상담 이력을 감사(audit) 목적으로 남겨야 하는가?</td><td>X</td><td>O</td></tr>
 *   <tr><td>개발/데모/짧은 세션 용도인가?</td><td>O</td><td>X (오버엔지니어링)</td></tr>
 * </table>
 *
 * <h3>JDBC로 전환하는 단계</h3>
 * <ol>
 *   <li><b>의존성 추가</b> (build.gradle):
 *     <pre>{@code
 *     implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc'
 *     runtimeOnly    'com.h2database:h2'  // 또는 PostgreSQL 드라이버
 *     }</pre>
 *   </li>
 *   <li><b>DataSource 설정</b> (application-jdbc.yml 참조)</li>
 *   <li><b>기존 Bean 비활성화</b>: ChatMemoryConfig의 chatMemoryRepository()를
 *     {@code @Profile("!jdbc")}로 한정한다. 그러면 JDBC 프로필에서는
 *     자동 구성된 {@code JdbcChatMemoryRepository}가 주입된다.</li>
 *   <li><b>실행</b>: {@code ./gradlew bootRun --args='--spring.profiles.active=jdbc'}</li>
 * </ol>
 *
 * <h3>스키마</h3>
 * Spring AI 1.0의 JdbcChatMemoryRepository는 다음과 같은 테이블을 사용한다
 * ({@code spring.ai.chat.memory.repository.jdbc.initialize-schema=embedded}일 때 자동 생성):
 *
 * <pre>{@code
 * CREATE TABLE SPRING_AI_CHAT_MEMORY (
 *     conversation_id VARCHAR(36) NOT NULL,
 *     content         TEXT        NOT NULL,
 *     type            VARCHAR(10) NOT NULL,
 *     "timestamp"     TIMESTAMP   NOT NULL,
 *     CONSTRAINT TYPE_CHECK CHECK (type IN ('USER','ASSISTANT','SYSTEM','TOOL'))
 * );
 * CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
 *     ON SPRING_AI_CHAT_MEMORY (conversation_id, "timestamp");
 * }</pre>
 *
 * <h3>운영 시 고려사항</h3>
 * <ul>
 *   <li><b>인덱스</b>: conversation_id가 세션 식별자이므로 반드시 인덱스 필요 (위 스키마에 포함)</li>
 *   <li><b>파티셔닝/TTL</b>: 대화 이력은 시간이 지날수록 누적된다.
 *       오래된 세션은 배치로 삭제하거나 별도 아카이브 테이블로 이관하는 정책이 필요하다.</li>
 *   <li><b>개인정보 보호</b>: 고객이 대화 중 전화번호/주소를 언급하면 평문으로 DB에 남는다.
 *       5주차(Guardrail)에서 다룰 입력 마스킹과 함께 저장 전 필터링을 적용해야 한다.</li>
 *   <li><b>PostgreSQL로 운영할 경우</b>:
 *     <pre>{@code
 *     spring.datasource.url=jdbc:postgresql://localhost:5432/baedal
 *     }</pre>
 *     4주차에서 PgVector를 띄우므로 동일 DB 인스턴스를 공유해도 된다.</li>
 * </ul>
 *
 * @see ChatMemoryConfig
 */
public final class JdbcChatMemoryExample {
    private JdbcChatMemoryExample() {}
}
