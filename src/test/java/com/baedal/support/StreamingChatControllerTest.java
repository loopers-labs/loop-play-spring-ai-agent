package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamingChatControllerTest {

    @Mock ChatClient.Builder builder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.StreamResponseSpec streamSpec;

    @Test
    void chatStream_appliesSystemPrompt_andEmitsTokensInOrder() {
        when(builder.defaultSystem(BaedalPrompt.SYSTEM_PROMPT)).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("주문", "번호", "확인", "필요"));

        StreamingChatController controller = new StreamingChatController(builder);
        Flux<String> stream = controller.chatStream(new ChatRequest("주문번호 2024 어디?"));

        List<String> emitted = stream.collectList().block();
        assertThat(emitted).containsExactly("주문", "번호", "확인", "필요");
        verify(builder).defaultSystem(BaedalPrompt.SYSTEM_PROMPT);
        verify(requestSpec).user("주문번호 2024 어디?");
    }

    @Test
    void chatStream_doesNotBuildChatClientPerRequest() {
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("a"));

        StreamingChatController controller = new StreamingChatController(builder);
        controller.chatStream(new ChatRequest("문의1")).collectList().block();
        controller.chatStream(new ChatRequest("문의2")).collectList().block();

        verify(builder, times(1)).build();
    }
}
