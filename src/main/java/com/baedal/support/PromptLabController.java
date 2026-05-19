package com.baedal.support;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/prompt-lab")
public class PromptLabController {

    private final ChatClient.Builder builder;

    @PostMapping
    public PromptLabResult experiment(@RequestBody PromptLabRequest request) {
        ChatClient client = builder
                .defaultSystem(request.systemPrompt())
                .build();

        List<SupportResponse> results = new ArrayList<>();
        for (int i = 0; i < request.repeat(); i++) {
            var prompt = client.prompt().user(request.message());
            if (request.temperature() != null) {
                prompt = prompt.options(OllamaOptions.builder().temperature(request.temperature()).build());
            }
            results.add(prompt.call().entity(SupportResponse.class));
        }

        return PromptLabResult.from(results, request.evalCriteria());
    }
}
