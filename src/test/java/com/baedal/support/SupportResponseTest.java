package com.baedal.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SupportResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_include_estimatedResolutionMinutes_field() throws Exception {
        var response = new SupportResponse(
            "테스트 요약",
            SupportResponse.Category.DELIVERY,
            SupportResponse.Urgency.NORMAL,
            "배달 현황을 확인하세요.",
            List.of("주문번호"),
            10,
            SupportResponse.Actionability.NEEDS_INFO
        );

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("estimatedResolutionMinutes");
        assertThat(response.estimatedResolutionMinutes()).isEqualTo(10);
    }

    @Test
    void should_have_complaint_category() {
        assertThat(SupportResponse.Category.valueOf("COMPLAINT"))
            .isEqualTo(SupportResponse.Category.COMPLAINT);
    }

    @Test
    void should_deserialize_from_json() throws Exception {
        String json = """
            {
              "summary": "라이더 사고 접수",
              "category": "COMPLAINT",
              "urgency": "HIGH",
              "nextAction": "보상팀에 전달합니다.",
              "neededInfo": ["주문번호", "사진"],
              "estimatedResolutionMinutes": 2880,
              "actionability": "NEEDS_REVIEW"
            }
            """;

        SupportResponse response = mapper.readValue(json, SupportResponse.class);

        assertThat(response.category()).isEqualTo(SupportResponse.Category.COMPLAINT);
        assertThat(response.estimatedResolutionMinutes()).isEqualTo(2880);
        assertThat(response.actionability()).isEqualTo(SupportResponse.Actionability.NEEDS_REVIEW);
    }

    @Test
    void should_have_all_actionability_values() {
        assertThat(SupportResponse.Actionability.values())
            .containsExactlyInAnyOrder(
                SupportResponse.Actionability.IMMEDIATE,
                SupportResponse.Actionability.NEEDS_INFO,
                SupportResponse.Actionability.NEEDS_REVIEW,
                SupportResponse.Actionability.ESCALATED
            );
    }

    @Test
    void should_include_actionability_in_json() throws Exception {
        var response = new SupportResponse(
            "취소 가능합니다.",
            SupportResponse.Category.ORDER,
            SupportResponse.Urgency.NORMAL,
            "주문을 취소합니다.",
            List.of(),
            10,
            SupportResponse.Actionability.IMMEDIATE
        );

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("actionability");
        assertThat(json).contains("IMMEDIATE");
    }
}