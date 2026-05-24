package com.baedal.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptLabResultTest {

    private static SupportResponse response(SupportResponse.Category category) {
        return new SupportResponse(
                "요약", category, SupportResponse.Urgency.NORMAL,
                "다음 액션", List.of(), 10, SupportResponse.Actionability.NEEDS_INFO
        );
    }

    @Test
    void from_calculates_consistency_when_all_same_category() {
        List<SupportResponse> results = List.of(
                response(SupportResponse.Category.DELIVERY),
                response(SupportResponse.Category.DELIVERY),
                response(SupportResponse.Category.DELIVERY)
        );

        PromptLabController.PromptLabResult result = PromptLabController.PromptLabResult.from(results);

        assertThat(result.totalRuns()).isEqualTo(3);
        assertThat(result.categoryConsistency()).isEqualTo(1.0);
        assertThat(result.categoryCounts().get("DELIVERY")).isEqualTo(3L);
    }

    @Test
    void from_calculates_consistency_with_mixed_categories() {
        List<SupportResponse> results = List.of(
                response(SupportResponse.Category.DELIVERY),
                response(SupportResponse.Category.DELIVERY),
                response(SupportResponse.Category.DELIVERY),
                response(SupportResponse.Category.COMPLAINT),
                response(SupportResponse.Category.COMPLAINT)
        );

        PromptLabController.PromptLabResult result = PromptLabController.PromptLabResult.from(results);

        assertThat(result.totalRuns()).isEqualTo(5);
        assertThat(result.categoryConsistency()).isEqualTo(0.6);
        assertThat(result.categoryCounts().get("DELIVERY")).isEqualTo(3L);
        assertThat(result.categoryCounts().get("COMPLAINT")).isEqualTo(2L);
    }

    @Test
    void from_returns_zero_consistency_for_empty_list() {
        PromptLabController.PromptLabResult result = PromptLabController.PromptLabResult.from(List.of());

        assertThat(result.totalRuns()).isEqualTo(0);
        assertThat(result.categoryConsistency()).isEqualTo(0.0);
    }
}