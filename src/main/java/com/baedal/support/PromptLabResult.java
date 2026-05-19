package com.baedal.support;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record PromptLabResult(
        int totalRuns,
        Map<String, Long> categoryCounts,
        Map<String, Long> urgencyCounts,
        double categoryConsistency,
        double nextActionComplianceRate,
        double prohibitionViolationRate,
        double neededInfoCompletenessRate,
        List<SupportResponse> individualResults
) {
    public static PromptLabResult from(List<SupportResponse> results, EvalCriteria criteria) {
        if (results.isEmpty()) {
            return new PromptLabResult(0, Map.of(), Map.of(), 0, 0, 0, 0, List.of());
        }

        Map<String, Long> catCounts = results.stream()
                .collect(Collectors.groupingBy(r -> r.category().name(), Collectors.counting()));

        Map<String, Long> urgCounts = results.stream()
                .collect(Collectors.groupingBy(r -> r.urgency().name(), Collectors.counting()));

        long maxCat = catCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);

        long compliantCount = results.stream()
                .filter(r -> criteria.allowedNextActions().contains(r.nextAction()))
                .count();

        long violationCount = results.stream()
                .filter(r -> criteria.violationKeywords().stream().anyMatch(kw -> r.summary().contains(kw)))
                .count();

        double neededInfoCompletenessRate = calcNeededInfoCompletenessRate(results, criteria);

        return new PromptLabResult(
                results.size(),
                catCounts,
                urgCounts,
                (double) maxCat / results.size(),
                (double) compliantCount / results.size(),
                (double) violationCount / results.size(),
                neededInfoCompletenessRate,
                results
        );
    }

    private static double calcNeededInfoCompletenessRate(List<SupportResponse> results, EvalCriteria criteria) {
        if (criteria.neededInfoRequirements().isEmpty()) {
            return 1.0;
        }
        List<SupportResponse> targeted = results.stream()
                .filter(r -> criteria.neededInfoRequirements().containsKey(r.nextAction()))
                .toList();
        if (targeted.isEmpty()) {
            return 1.0;
        }
        long completeCount = targeted.stream()
                .filter(r -> {
                    String requiredKeyword = criteria.neededInfoRequirements().get(r.nextAction());
                    return r.neededInfo() != null && r.neededInfo().contains(requiredKeyword);
                })
                .count();
        return (double) completeCount / targeted.size();
    }
}
