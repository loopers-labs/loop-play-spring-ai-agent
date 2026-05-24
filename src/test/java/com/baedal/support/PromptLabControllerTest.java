package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PromptLabController.class)
class PromptLabControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatClient.Builder builder;

    @BeforeEach
    void setUp() {
        SupportResponse response = new SupportResponse(
            "배달 현황을 조회하겠습니다.",
            SupportResponse.Category.DELIVERY,
            SupportResponse.Urgency.NORMAL,
            "주문 현황 페이지를 확인하세요.",
            List.of("주문번호"),
            10,
            SupportResponse.Actionability.NEEDS_INFO
        );

        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(SupportResponse.class)).thenReturn(response);
    }

    @Test
    void experiment_returns_result_with_correct_total_runs() throws Exception {
        mockMvc.perform(post("/api/v1/prompt-lab")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systemPrompt": "당신은 배달 고객 상담 AI입니다.",
                      "message": "배달 어디쯤이에요?",
                      "repeat": 3
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalRuns").value(3))
            .andExpect(jsonPath("$.categoryConsistency").value(1.0));
    }

    @Test
    void experiment_counts_categories() throws Exception {
        mockMvc.perform(post("/api/v1/prompt-lab")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systemPrompt": "당신은 배달 고객 상담 AI입니다.",
                      "message": "배달 어디쯤이에요?",
                      "repeat": 2
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categoryCounts.DELIVERY").value(2));
    }

    @Test
    void experiment_returns_400_when_repeat_is_zero() throws Exception {
        mockMvc.perform(post("/api/v1/prompt-lab")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systemPrompt": "당신은 배달 고객 상담 AI입니다.",
                      "message": "배달 어디쯤이에요?",
                      "repeat": 0
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void experiment_returns_400_when_repeat_exceeds_max() throws Exception {
        mockMvc.perform(post("/api/v1/prompt-lab")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systemPrompt": "당신은 배달 고객 상담 AI입니다.",
                      "message": "배달 어디쯤이에요?",
                      "repeat": 1000
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void experiment_returns_400_when_message_is_blank() throws Exception {
        mockMvc.perform(post("/api/v1/prompt-lab")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systemPrompt": "당신은 배달 고객 상담 AI입니다.",
                      "message": "",
                      "repeat": 3
                    }
                    """))
            .andExpect(status().isBadRequest());
    }
}