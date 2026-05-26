package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);

    private static final OllamaOptions JSON_OPTIONS = OllamaOptions.builder()
            .format("json")
            .build();

    public SupportController(ChatClient.Builder builder,
                             PerformanceLoggingAdvisor performanceLoggingAdvisor,
                             OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceLoggingAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req) {
        var converter = new BeanOutputConverter<>(SupportResponse.class, LENIENT_MAPPER);
        return chatClient.prompt()
                .user(req.message())
                .options(JSON_OPTIONS)
                .call()
                .entity(converter);
    }
}