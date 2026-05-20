package com.baedal.support;


import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

@Configuration
@Slf4j
public class OllamaLoggingConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    log.info("=== 실제 Ollama 요청 === {}",new String(body, StandardCharsets.UTF_8));
                    return execution.execute(request, body);
                });
    }
}
