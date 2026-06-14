package com.baedal.support.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Layer 3 — 도구가 못 잡고 던진 예외를 받는 전역 backstop.
 * <p>
 * 도구 내부 try-catch(Layer 2)를 빠져나간 예외(try 밖 throw, try-catch 없는 툴, 라이브러리 내부 throw)는
 * Spring AI 기본 {@code DefaultToolExecutionExceptionProcessor}가 {@code e.getMessage()} 원문을 그대로
 * LLM에 넘긴다(정보 유출 + 응답 일관성 상실). 이 커스텀 빈이 기본 빈을 대체해 그 경로를 막는다.
 * <ul>
 *     <li>실제 예외·스택은 {@code log.error}로 내부 로그에만 남긴다.</li>
 *     <li>LLM에는 raw 원문 대신 <b>정제된 고정 문구</b>만 전달한다.</li>
 * </ul>
 * Layer 3까지 새는 예외는 거의 항상 코드 버그(재시도 무의미)이므로 transient/permanent 구분 없이
 * "상담사 연결" 한 문구로 고정한다. 진짜 일시 오류는 Layer 2가 잡는다.
 */
@Slf4j
@Configuration
public class ToolExecutionConfig {

    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return ex -> {
            log.error("[Tool] 처리되지 않은 예외 — tool={}", ex.getToolDefinition().name(), ex.getCause());

            return "도구 처리 중 오류가 발생했습니다. "
                    + "고객에게는 상담사 연결(1600-0987)을 안내하세요.";
        };
    }
}
