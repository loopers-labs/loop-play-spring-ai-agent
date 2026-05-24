package com.baedal.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/prompt-lab")
public class PromptLabController {

    private final ChatClient.Builder builder;

    // TODO [2단계]: 프롬프트 정량 비교 실험 엔드포인트를 구현하라.
    //
    // 구현 힌트:
    // 1. req.systemPrompt()를 System Prompt로 설정한 ChatClient를 빌드한다.
    // 2. req.repeat() 횟수만큼 반복하여 .entity(SupportResponse.class)를 호출한다.
    // 3. 결과 리스트를 PromptLabResult.from()에 넘겨 통계를 계산한다.
    //
    // 실험 후:
    // - 단순 프롬프트 vs 구조화된 프롬프트로 각 5회 호출
    // - categoryConsistency 수치를 비교하여 README에 기록
    @PostMapping
    public PromptLabResult experiment(@Valid @RequestBody PromptLabRequest req) {
        ChatClient client = builder
                .defaultSystem(req.systemPrompt())
                .build();

        List<SupportResponse> results = new ArrayList<>();
        for (int i = 0; i < req.repeat(); i++) {
            results.add(client.prompt()
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
