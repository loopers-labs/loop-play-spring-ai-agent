package com.baedal.support;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration
@Slf4j
public class OllamaConfig {

    // Prompt Logging 용
    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(5));  // Netty 레벨 timeout
        ClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    log.info("=== 실제 Ollama 요청 === {}",new String(body, StandardCharsets.UTF_8));
                    return execution.execute(request, body);
                })
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));
    }

    // Timeout 설정
//    @Bean
//    public OllamaApi ollamaApi() {
//        HttpClient httpClient = HttpClient.create()
//                .responseTimeout(Duration.ofMinutes(5));  // Netty 레벨 timeout
//
//        ClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);
//
////        RestClient restClient = RestClient.builder()
////                .requestFactory(new ReactorClientHttpRequestFactory(httpClient))
////                .build();
////        return new OllamaApi("http://localhost:11434", restClient);
//
//        return OllamaApi.builder()
//                .baseUrl("http://localhost:11434")
//                .restClientBuilder(RestClient.builder()
//                        .requestInterceptor((request, body, execution) -> {
//                            log.info("=== 실제 Ollama 요청 === {}",new String(body, StandardCharsets.UTF_8));
//                            return execution.execute(request, body);
//                        })
//                        .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
//                .build();
//    }
}
