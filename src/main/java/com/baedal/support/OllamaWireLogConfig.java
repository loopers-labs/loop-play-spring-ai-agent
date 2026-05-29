package com.baedal.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Ollama로 나가는 HTTP 요청/응답의 <b>원본 JSON 바디</b>를 로그로 남기는 설정(dev 전용).
 * <p>
 * {@code OllamaApi.chat()}(동기 경로)은 {@code RestClient}로 {@code /api/chat}를 호출하지만
 * 요청/응답 바디를 로깅하지 않는다. {@code SimpleLoggerAdvisor}는 그 위 계층이라 이미
 * {@code ChatResponse}로 매핑이 끝난 객체만 본다. 따라서 Ollama 네이티브 JSON
 * (예: {@code message}, {@code tool_calls}, {@code done_reason}, {@code prompt_eval_count},
 * {@code eval_count})은 기본 로그에 나타나지 않는다.
 * <p>
 * Spring Boot의 공용 {@code RestClient.Builder}에 {@link RestClientCustomizer}로 인터셉터를
 * 붙이면 {@code OllamaApiAutoConfiguration}이 {@code ObjectProvider<RestClient.Builder>}로
 * 그 빌더를 주입받으므로, OllamaApi의 RestClient에 그대로 적용된다.
 * <p>
 * 프롬프트 전문이 그대로 노출되므로 dev 프로파일에서만 활성화한다(logback의 TRACE 파일과 동일 정책).
 */
@Slf4j
@Configuration
@Profile("dev")
public class OllamaWireLogConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Bean
    RestClientCustomizer ollamaWireLoggingCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            boolean chat = request.getURI().getPath().contains("/api/chat");

            if (chat && log.isDebugEnabled()) {
                log.debug("[Ollama 요청] {} {}\n{}", request.getMethod(), request.getURI(), pretty(body));
            }

            ClientHttpResponse response = execution.execute(request, body);
            if (!chat || !log.isDebugEnabled()) {
                return response;
            }

            // 응답 바디를 한 번 읽어 로깅하고, 버퍼링 래퍼로 되돌려 OllamaApi 역직렬화가 다시 읽을 수 있게 한다.
            byte[] bytes = StreamUtils.copyToByteArray(response.getBody());
            log.debug("[Ollama 응답] status={}\n{}", response.getStatusCode(), pretty(bytes));
            return new BufferedClientHttpResponse(response, bytes);
        });
    }

    /**
     * 로그 가독성을 위해 JSON 바디를 들여쓰기(pretty)해서 돌려준다(로깅 전용 — 실제 전송 바이트는 그대로다).
     * JSON이 아니거나 비어 있으면 원본 문자열을 그대로 쓴다. 비ASCII(한글)는 이스케이프하지 않는다.
     */
    private static String pretty(byte[] json) {
        if (json == null || json.length == 0) {
            return "(빈 바디)";
        }
        try {
            JsonNode tree = MAPPER.readTree(json);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (IOException notJson) {
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    /** 원본 응답의 상태·헤더는 위임하고, 바디만 버퍼한 바이트로 재공급한다. */
    private static final class BufferedClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
