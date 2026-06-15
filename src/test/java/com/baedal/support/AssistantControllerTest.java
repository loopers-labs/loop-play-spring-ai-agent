package com.baedal.support;

import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
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

@WebMvcTest(AssistantController.class)
class AssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PerformanceLoggingAdvisor performanceAdvisor;

    @MockBean
    private MessageChatMemoryAdvisor memoryAdvisor;

    @MockBean
    private QuestionAnswerAdvisor ragAdvisor;

    @MockBean
    private InputGuardrailAdvisor inputGuardrail;

    @MockBean
    private OutputGuardrailAdvisor outputGuardrail;

    @MockBean
    private HandoffDetector handoffDetector;

    @MockBean
    private OrderTools orderTools;

    /**
     * round5 AssistantController는 ChatClient.Builder를 주입받아 요청마다 체인을 조립한다.
     * RETURNS_SELF가 defaultSystem/defaultAdvisors/defaultTools 체이닝을 자동 처리한다.
     */
    @TestConfiguration
    static class Config {
        @Bean
        ChatClient.Builder mockChatClientBuilder() {
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenReturn("배달 현황 확인 중입니다.");

            ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_SELF);
            when(builder.build()).thenReturn(chatClient);
            return builder;
        }
    }

    @BeforeEach
    void setUp() {
        // Handoff 선검사는 LLM 호출 전에 실행되므로, 일반 케이스는 전환 없음으로 스텁한다.
        when(handoffDetector.detect(anyString())).thenReturn(HandoffDetector.HandoffDecision.none());
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