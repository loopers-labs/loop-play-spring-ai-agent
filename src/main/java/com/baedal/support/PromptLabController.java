package com.baedal.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/prompt-lab")
public class PromptLabController {

    static final int MAX_REPEAT = 20;

    private final ChatClient.Builder builder;

    public PromptLabController(ChatClient.Builder builder) {
        this.builder = builder;
    }

    @PostMapping
    public PromptLabResult experiment(@Valid @RequestBody PromptLabRequest req) {
        int runs = Math.max(0, req.repeat());
        if (runs == 0) {
            return PromptLabResult.from(List.of());
        }

        ChatClient chatClient = builder
                .defaultSystem(req.systemPrompt())
                .build();

        List<SupportResponse> results = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            SupportResponse r = chatClient.prompt()
                    .user(req.message())
                    .call()
                    .entity(SupportResponse.class);
            results.add(r);
        }
        return PromptLabResult.from(results);
    }

    public record PromptLabRequest(
            @NotBlank String systemPrompt,
            @NotBlank String message,
            @Max(MAX_REPEAT) int repeat
    ) {}

    public record PromptLabResult(
            int totalRuns,
            Map<String, Long> categoryCounts,
            Map<String, Long> urgencyCounts,
            double categoryConsistency
    ) {
        public static PromptLabResult from(List<SupportResponse> results) {
            var catCounts = results.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.category().name(), Collectors.counting()));
            var urgCounts = results.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.urgency().name(), Collectors.counting()));
            long maxCat = catCounts.values().stream()
                    .mapToLong(Long::longValue).max().orElse(0);

            return new PromptLabResult(
                    results.size(), catCounts, urgCounts,
                    results.isEmpty() ? 0 : (double) maxCat / results.size()
            );
        }
    }
}
