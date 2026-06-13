-- Spring AI 1.0.0의 JdbcChatMemoryRepository는 H2용 schema-h2.sql을 번들하지 않는다.
-- (postgresql/mysql/mariadb/hsqldb/sqlserver만 포함) → 직접 제공하고
--  spring.ai.chat.memory.repository.jdbc.schema 로 이 경로를 가리킨다.
-- 파일 기반 H2(h2:file) 재시작 시 재실행되므로 IF NOT EXISTS로 멱등 보장.
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36)  NOT NULL,
    content         CLOB         NOT NULL,
    type            VARCHAR(10)  NOT NULL,
    "timestamp"     TIMESTAMP    NOT NULL,
    CONSTRAINT TYPE_CHECK CHECK (type IN ('USER','ASSISTANT','SYSTEM','TOOL'))
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, "timestamp");
