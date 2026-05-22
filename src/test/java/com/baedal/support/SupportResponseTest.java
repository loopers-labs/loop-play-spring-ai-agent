package com.baedal.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportResponseTest {

    private SupportResponse build(List<String> neededInfo, Integer minutes) {
        return new SupportResponse(
                "s",
                SupportResponse.Category.ORDER,
                SupportResponse.Urgency.NORMAL,
                "n",
                neededInfo,
                minutes,
                SupportResponse.Confidence.MEDIUM
        );
    }

    @Test
    void neededInfo_isDefensivelyCopied_soOriginalMutationDoesNotLeak() {
        List<String> original = new ArrayList<>(List.of("주문번호"));
        SupportResponse r = build(original, 5);

        original.add("주문 시각");

        assertThat(r.neededInfo()).containsExactly("주문번호");
    }

    @Test
    void neededInfo_isUnmodifiable_fromOutside() {
        SupportResponse r = build(new ArrayList<>(List.of("a")), 5);

        assertThatThrownBy(() -> r.neededInfo().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void neededInfo_defaultsToEmptyList_whenNull() {
        SupportResponse r = build(null, null);

        assertThat(r.neededInfo()).isEmpty();
    }

    @Test
    void negativeEstimatedResolutionMinutes_isRejected() {
        assertThatThrownBy(() -> build(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estimatedResolutionMinutes");
    }

    @Test
    void zeroEstimatedResolutionMinutes_isAllowed() {
        SupportResponse r = build(List.of(), 0);

        assertThat(r.estimatedResolutionMinutes()).isZero();
    }
}
