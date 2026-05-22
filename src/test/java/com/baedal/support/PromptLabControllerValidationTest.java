package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PromptLabController.class)
class PromptLabControllerValidationTest {

    @org.springframework.beans.factory.annotation.Autowired MockMvc mvc;
    @MockitoBean ChatClient.Builder builder;

    @Test
    void experiment_blankSystemPrompt_returns400_andDoesNotBuildChatClient() throws Exception {
        mvc.perform(post("/api/v1/prompt-lab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemPrompt\":\"\",\"message\":\"msg\",\"repeat\":3}"))
                .andExpect(status().isBadRequest());
        verify(builder, never()).build();
    }

    @Test
    void experiment_blankMessage_returns400() throws Exception {
        mvc.perform(post("/api/v1/prompt-lab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemPrompt\":\"sys\",\"message\":\"   \",\"repeat\":3}"))
                .andExpect(status().isBadRequest());
        verify(builder, never()).build();
    }

    @Test
    void experiment_repeatAboveMax_returns400_andDoesNotBuildChatClient() throws Exception {
        int over = PromptLabController.MAX_REPEAT + 1;
        mvc.perform(post("/api/v1/prompt-lab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemPrompt\":\"sys\",\"message\":\"msg\",\"repeat\":" + over + "}"))
                .andExpect(status().isBadRequest());
        verify(builder, never()).build();
    }
}
