package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SupportController.class)
class SupportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PerformanceLoggingAdvisor performanceAdvisor;

    @MockBean
    private MessageChatMemoryAdvisor memoryAdvisor;

    @MockBean
    private OrderTools orderTools;

    /**
     * SupportController는 요청마다 builder 체인을 실행한다.
     * RETURNS_SELF가 defaultSystem/defaultAdvisors/defaultTools 체이닝을 자동 처리한다.
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
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
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
    void triage_returns_200_with_support_response() throws Exception {
        mockMvc.perform(post("/api/v1/support")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"주문번호 2024-1234 배달 어디쯤에 있어요?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("DELIVERY"))
            .andExpect(jsonPath("$.urgency").value("NORMAL"))
            .andExpect(jsonPath("$.estimatedResolutionMinutes").value(10));
    }

    @Test
    void triage_returns_503_when_llm_call_fails() throws Exception {
        reset(Config.callSpec);
        when(Config.callSpec.entity(SupportResponse.class))
                .thenThrow(new RuntimeException("LLM unavailable"));

        mockMvc.perform(post("/api/v1/support")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"주문번호 2024-1234 배달 어디쯤에 있어요?\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }
}