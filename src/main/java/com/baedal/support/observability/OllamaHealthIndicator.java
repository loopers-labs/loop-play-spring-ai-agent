package com.baedal.support.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component("ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    private final RestClient client;

    @Autowired
    public OllamaHealthIndicator(RestClient.Builder builder,
                                 @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        this(builder.baseUrl(baseUrl).build());
    }

    OllamaHealthIndicator(RestClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            String body = client.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(String.class);
            return Health.up()
                    .withDetail("responseLength", body == null ? 0 : body.length())
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
