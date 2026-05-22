package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StreamingChatController.class)
@Import(StreamingChatControllerValidationTest.MockChatClientConfig.class)
class StreamingChatControllerValidationTest {

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
            when(b.build()).thenReturn(chatClient);
            return b;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ChatClient chatClient;

    @Test
    void chatStream_blankMessage_returns400_andDoesNotCallChatClient() throws Exception {
        mvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(chatClient, never()).prompt();
    }

    @Test
    void chatStream_missingMessage_returns400() throws Exception {
        mvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(chatClient, never()).prompt();
    }
}
