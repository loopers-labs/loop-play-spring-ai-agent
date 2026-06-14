package com.baedal.support.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionConfigTest {

    private final ToolExecutionExceptionProcessor processor =
            new ToolExecutionConfig().toolExecutionExceptionProcessor();

    @Test
    void 못_잡은_예외는_raw_원문_없이_상담사_연결_고정문구를_반환한다() {
        var rawMessage = "simulated Tool failure";
        var toolDef = ToolDefinition.builder()
                .name("getOrderDetail")
                .description("주문 상세 조회")
                .inputSchema("{}")
                .build();
        var exception = new ToolExecutionException(toolDef, new RuntimeException(rawMessage));

        var result = processor.process(exception);

        assertThat(result)
                .contains("상담사")
                .contains("1600-0987")
                .doesNotContain(rawMessage); // raw 예외 원문이 LLM에 새지 않는다
    }
}
