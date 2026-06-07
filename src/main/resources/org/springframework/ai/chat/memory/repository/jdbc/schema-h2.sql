-- Spring AI 1.0.0 의 spring-ai-model-chat-memory-repository-jdbc 는 h2 스키마를 패키징하지 않는다
-- (postgresql/sqlserver/hsqldb/mariadb 만 제공). H2 datasource 는 platform=h2 로 감지되어
-- classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-h2.sql 을 찾으므로,
-- 동일 경로에 직접 제공한다. 내용은 schema-postgresql.sql 과 동등(H2 PostgreSQL 모드 호환).
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");
