package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

class PerformanceLoggingAdvisorTest {

    private final PerformanceLoggingAdvisor advisor = new PerformanceLoggingAdvisor();

    @Test
    void getName_returns_expected_name() {
        assertThat(advisor.getName()).isEqualTo("PerformanceLoggingAdvisor");
    }

    @Test
    void adviseCall_delegates_to_chain_and_returns_response() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse expected = mock(ChatClientResponse.class);

        when(chain.nextCall(request)).thenReturn(expected);
        when(expected.chatResponse()).thenReturn(null);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(expected);
        verify(chain).nextCall(request);
    }

    @Test
    void adviseCall_handles_null_chatResponse_safely() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = mock(ChatClientResponse.class);

        when(chain.nextCall(request)).thenReturn(response);
        when(response.chatResponse()).thenReturn(null);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isNotNull();
    }

    @Test
    void adviseCall_rethrows_exception_from_chain() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        when(chain.nextCall(request)).thenThrow(new RuntimeException("LLM unavailable"));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("LLM unavailable");
    }

    @Test
    void adviseCall_handles_null_usage_safely() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);

        when(chain.nextCall(request)).thenReturn(response);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(null);

        assertThatNoException().isThrownBy(() -> advisor.adviseCall(request, chain));
    }
}