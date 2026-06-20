package com.baedal.assistant;

import com.baedal.assistant.tool.OrderTools;
import com.baedal.support.BaedalSupportApplication;
import com.baedal.support.PerformanceLoggingAdvisor;
import com.baedal.support.observability.AgentMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssistantController.class)
@ContextConfiguration(classes = BaedalSupportApplication.class)
@Import(AssistantControllerValidationTest.MockChatClientConfig.class)
class AssistantControllerValidationTest {

    @TestConfiguration
    static class MockChatClientConfig {
        @Bean
        ChatClient chatClient() {
            return mock(ChatClient.class);
        }

        @Bean
        ChatClient.Builder chatClientBuilder(ChatClient chatClient) {
            ChatClient.Builder b = mock(ChatClient.Builder.class);
            when(b.defaultSystem(anyString())).thenReturn(b);
            when(b.defaultAdvisors(any(Advisor[].class))).thenReturn(b);
            when(b.defaultTools(any(Object[].class))).thenReturn(b);
            when(b.build()).thenReturn(chatClient);
            return b;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ChatClient chatClient;
    @MockitoBean com.baedal.support.guardrail.InputGuardrailAdvisor inputGuardrail;
    @MockitoBean com.baedal.support.guardrail.OutputGuardrailAdvisor outputGuardrail;
    @MockitoBean com.baedal.support.guardrail.HandoffDetector handoffDetector;
    @MockitoBean MessageChatMemoryAdvisor memoryAdvisor;
    @MockitoBean QuestionAnswerAdvisor ragAdvisor;
    @MockitoBean PerformanceLoggingAdvisor advisor;
    @MockitoBean AgentMetrics metrics;
    @MockitoBean OrderTools orderTools;

    @Test
    void ask_blankMessage_returns400_andDoesNotCallChatClient() throws Exception {
        mvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
        verify(chatClient, never()).prompt();
    }

    @Test
    void ask_missingMessage_returns400() throws Exception {
        mvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(chatClient, never()).prompt();
    }

    @Test
    void ask_missingSessionId_returns400_andDoesNotCallChatClient() throws Exception {
        mvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"2024-1234 어디쯤이에요?\"}"))
                .andExpect(status().isBadRequest());
        verify(chatClient, never()).prompt();
    }
}
