package com.baedal.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceLoggingAdvisor;

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);

    private static final OllamaOptions JSON_OPTIONS = OllamaOptions.builder()
            .format("json")
            .build();

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req) {
        var converter = new BeanOutputConverter<>(SupportResponse.class, LENIENT_MAPPER);
        return builder.defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceLoggingAdvisor)
                .build()
                .prompt()
                .user(req.message())
                .options(JSON_OPTIONS)
                .call()
                .entity(converter);
    }
}
