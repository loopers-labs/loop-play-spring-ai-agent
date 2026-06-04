package com.baedal.support.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@Profile("jdbc")
@RequiredArgsConstructor
public class ChatMemoryRawRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findAll() {
        return jdbcTemplate.queryForList(
            "SELECT conversation_id, type, \"timestamp\", SUBSTRING(content, 1, 80) as content" +
            " FROM SPRING_AI_CHAT_MEMORY ORDER BY \"timestamp\""
        );
    }
}
