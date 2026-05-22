package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupportController.class)
@Import(SupportControllerValidationTest.MockChatClientConfig.class)
class SupportControllerValidationTest {

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
            when(b.build()).thenReturn(chatClient);
            return b;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ChatClient chatClient;
    @MockitoBean PerformanceLoggingAdvisor advisor;

    @Test
    void triage_blankMessage_returns400_andDoesNotCallChatClient() throws Exception {
        mvc.perform(post("/api/v1/support")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
        verify(chatClient, never()).prompt();
    }

    @Test
    void triage_missingMessage_returns400() throws Exception {
        mvc.perform(post("/api/v1/support")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(chatClient, never()).prompt();
    }
}
