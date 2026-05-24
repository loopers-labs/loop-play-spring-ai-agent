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

@WebMvcTest(SupportController.class)
class SupportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatClient.Builder builder;

    @MockBean
    private PerformanceLoggingAdvisor performanceAdvisor;

    private SupportResponse deliveryResponse;

    @BeforeEach
    void setUp() {
        deliveryResponse = new SupportResponse(
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
        when(builder.defaultAdvisors(performanceAdvisor)).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(SupportResponse.class)).thenReturn(deliveryResponse);
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
    void triage_uses_system_prompt() throws Exception {
        mockMvc.perform(post("/api/v1/support")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"테스트\"}"))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(builder).defaultSystem(BaedalPrompt.SYSTEM_PROMPT);
    }

    @Test
    void triage_returns_503_when_llm_call_fails() throws Exception {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(performanceAdvisor)).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(SupportResponse.class)).thenThrow(new RuntimeException("LLM unavailable"));

        mockMvc.perform(post("/api/v1/support")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"주문번호 2024-1234 배달 어디쯤에 있어요?\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }
}