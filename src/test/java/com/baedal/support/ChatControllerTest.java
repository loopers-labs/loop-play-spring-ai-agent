package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock ChatClient.Builder builder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock PerformanceLoggingAdvisor advisor;

    private void wireChain() {
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
    }

    @Test
    void chat_appliesAdvisorAndReturnsContent() {
        wireChain();
        when(callSpec.content()).thenReturn("안녕하세요!");

        ChatController controller = new ChatController(builder, advisor);
        String result = controller.chat(new ChatRequest("안녕"));

        assertThat(result).isEqualTo("안녕하세요!");
        verify(builder).defaultAdvisors(advisor);
        verify(requestSpec).user("안녕");
    }

    @Test
    void chat_doesNotBuildChatClientPerRequest() {
        // ChatController가 매 요청마다 build() 를 호출하면 advisor 측정이 매번 새 ChatClient에
        // 붙어버려서 단계 4 의 Round 1 토큰 비교 측정이 무너진다. 생성자에서 한 번만 build 되는지 가드.
        wireChain();
        when(callSpec.content()).thenReturn("a");

        ChatController controller = new ChatController(builder, advisor);
        controller.chat(new ChatRequest("문의1"));
        controller.chat(new ChatRequest("문의2"));

        verify(builder, times(1)).build();
        verify(builder, times(1)).defaultAdvisors(advisor);
    }
}
