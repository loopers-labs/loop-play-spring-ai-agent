package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PromptLabController.class)
class PromptLabControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * PromptLabController는 생성자에서 ChatClient를 한 번만 build하고
     * systemPrompt는 요청별로 .prompt().system(...)에 위임한다.
     * @WebMvcTest 타이밍을 고려해 @TestConfiguration으로 mock 체인을 구성한다.
     */
    @TestConfiguration
    static class Config {
        static ChatClient.CallResponseSpec callSpec;

        @Bean
        ChatClient.Builder mockChatClientBuilder() {
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
            callSpec = mock(ChatClient.CallResponseSpec.class);

            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.system(anyString())).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(callSpec);

            ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_SELF);
            when(builder.build()).thenReturn(chatClient);
            return builder;
        }
    }

    @BeforeEach
    void setUp() {
        reset(Config.callSpec);
        when(Config.callSpec.entity(SupportResponse.class)).thenReturn(new SupportResponse(
                "배달 현황을 조회하겠습니다.",
                SupportResponse.Category.DELIVERY,
                SupportResponse.Urgency.NORMAL,
                "주문 현황 페이지를 확인하세요.",
                List.of("주문번호"),
                10,
                SupportResponse.Actionability.NEEDS_INFO
        ));
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