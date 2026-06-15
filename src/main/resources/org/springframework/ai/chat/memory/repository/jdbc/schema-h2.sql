-- 3단계 — Spring AI 1.0.0 의 JDBC ChatMemory 스타터는 schema-h2.sql 을 제공하지 않는다.
-- (postgresql / sqlserver / hsqldb / mariadb 만 포함). application-jdbc.yml 의
-- H2 URL 이 MODE=PostgreSQL 이므로 schema-postgresql.sql 과 동일한 내용을 둔다.
-- 이 파일이 classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-h2.sql 로
-- 인식되어 JdbcChatMemoryRepositorySchemaInitializer 가 자동 실행한다.
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");