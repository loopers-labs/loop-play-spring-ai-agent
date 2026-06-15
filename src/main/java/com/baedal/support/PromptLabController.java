package com.baedal.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    private final ChatClient chatClient;

    public PromptLabController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @PostMapping
    public PromptLabResult experiment(@Valid @RequestBody PromptLabRequest req) {
        List<SupportResponse> results = new ArrayList<>();
        for (int i = 0; i < req.repeat(); i++) {
            results.add(chatClient.prompt()
                    .system(req.systemPrompt())
                    .user(req.message())
                    .call()
                    .entity(SupportResponse.class));
        }
        return PromptLabResult.from(results);
    }

    public record PromptLabRequest(
            @NotBlank String systemPrompt,
            @NotBlank String message,
            @Min(1) @Max(100) int repeat
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
