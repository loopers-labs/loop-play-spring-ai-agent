package com.baedal.support;

import com.baedal.support.memory.ChatMemoryConfig;
import com.baedal.support.tool.OrderTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AssistantController.class,
        excludeAutoConfiguration = {})
class AssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PerformanceLoggingAdvisor performanceAdvisor;

    @MockBean
    private OrderTools orderTools;

    @MockBean
    private MessageChatMemoryAdvisor memoryAdvisor;

    @MockBean
    private ChatMemoryConfig chatMemoryConfig;

    /**
     * AssistantController는 조립된 ChatClient를 주입받는다.
     * @WebMvcTest는 AssistantChatClientConfig를 로드하지 않으므로
     * 테스트용 assistantChatClient Bean을 직접 제공한다.
     */
    @TestConfiguration
    static class Config {
        @Bean
        ChatClient assistantChatClient() {
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenReturn("배달 현황 확인 중입니다.");

            return chatClient;
        }
    }

    @Test
    void ask_returns_200_with_string_response() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"주문번호 2024-1234 어디쯤이에요?\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void ask_returns_400_when_message_is_blank() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ask_returns_400_when_message_exceeds_1000_chars() throws Exception {
        mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"" + "A".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
    }
}