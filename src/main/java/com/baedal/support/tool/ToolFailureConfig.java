package com.baedal.support.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tool 실행 실패를 LLM 입력으로 숨기지 않고 컨트롤러 fallback 경로로 전파한다.
 */
@Slf4j
@Configuration
public class ToolFailureConfig {

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return exception -> {
            log.error("[Tool] 실행 실패 — tool={}", toolName(exception), exception);
            throw exception;
        };
    }

    private String toolName(ToolExecutionException exception) {
        if (exception.getToolDefinition() == null) {
            return "unknown";
        }
        return exception.getToolDefinition().name();
    }
}
