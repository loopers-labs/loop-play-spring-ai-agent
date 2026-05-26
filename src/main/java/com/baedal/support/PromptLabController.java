package com.baedal.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/prompt-lab")
public class PromptLabController {

    private final ChatClient chatClient;

    public PromptLabController(ChatClient.Builder builder, PerformanceLoggingAdvisor performanceAdvisor) {
        // systemPrompt는 요청마다 다르므로 .defaultSystem()을 미리 설정하지 않음.
        // prompt().system(...)으로 요청 시점에 주입.
        this.chatClient = builder
                .defaultAdvisors(performanceAdvisor)
                .build();
    }

    @PostMapping
    public PromptLabResult experiment(@Valid @RequestBody PromptLabRequest req) {
        List<SupportResponse> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < req.repeat(); i++) {
            try {
                results.add(chatClient.prompt()
                        .system(req.systemPrompt())
                        .user(req.message())
                        .call()
                        .entity(SupportResponse.class));
            } catch (Exception e) {
                log.warn("PromptLab iteration {}/{} failed", i + 1, req.repeat(), e);
                errors.add("Iteration " + (i + 1) + ": " + e.getMessage());
            }
        }
        return PromptLabResult.from(results, errors);
    }

    public record PromptLabRequest(
            @NotBlank(message = "systemPrompt는 필수입니다")
            @Size(max = 8000, message = "systemPrompt는 8000자를 초과할 수 없습니다")
            String systemPrompt,

            @NotBlank(message = "message는 필수입니다")
            @Size(max = 1000, message = "message는 1000자를 초과할 수 없습니다")
            String message,

            @Min(value = 1, message = "반복 횟수는 1 이상이어야 합니다")
            @Max(value = 100, message = "반복 횟수는 100을 초과할 수 없습니다")
            int repeat
    ) {}

    public record PromptLabResult(
            int totalRuns,
            int successfulRuns,
            List<String> errors,
            Map<String, Long> categoryCounts,
            Map<String, Long> urgencyCounts,
            double categoryConsistency
    ) {
        public PromptLabResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            categoryCounts = categoryCounts == null ? Map.of() : Map.copyOf(categoryCounts);
            urgencyCounts = urgencyCounts == null ? Map.of() : Map.copyOf(urgencyCounts);
        }

        public static PromptLabResult from(List<SupportResponse> results, List<String> errors) {
            var catCounts = results.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.category().name(), Collectors.counting()));
            var urgCounts = results.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.urgency().name(), Collectors.counting()));
            long maxCat = catCounts.values().stream()
                    .mapToLong(Long::longValue).max().orElse(0);

            int total = results.size() + errors.size();
            return new PromptLabResult(
                    total,
                    results.size(),
                    errors,
                    catCounts,
                    urgCounts,
                    results.isEmpty() ? 0 : (double) maxCat / results.size()
            );
        }
    }
}
