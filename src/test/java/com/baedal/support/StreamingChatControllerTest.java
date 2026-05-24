package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StreamingChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OllamaChatModel chatModel;

    @Test
    void chatStream_returns_text_event_stream() throws Exception {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("배달 현황 확인"))))));

        mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"주문번호 2024-1234 배달 어디쯤에 있어요?\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    void chatStream_passes_system_prompt_to_model() throws Exception {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.stream(captor.capture()))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("배달 현황 확인"))))));

        mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"주문번호 2024-1234\"}"))
                .andExpect(status().isOk());

        String systemContent = captor.getValue().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .map(m -> m.getText())
                .findFirst()
                .orElse("");
        assertThat(systemContent).contains("[역할]");
    }
}