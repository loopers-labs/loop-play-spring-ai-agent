package com.baedal.support;

import com.baedal.support.observability.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerformanceLoggingAdvisorTest {

    @Mock CallAdvisorChain chain;
    @Mock ChatClientRequest request;
    @Mock ChatClientResponse response;
    @Mock ChatResponse chatResponse;
    @Mock ChatResponseMetadata metadata;
    @Mock Usage usage;

    @Test
    void adviseCall_delegatesToChain_andReturnsDownstreamResponse() {
        when(chain.nextCall(request)).thenReturn(response);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(42);
        when(usage.getCompletionTokens()).thenReturn(17);
        when(usage.getTotalTokens()).thenReturn(59);

        ChatClientResponse actual = newAdvisor().adviseCall(request, chain);

        assertThat(actual).isSameAs(response);
        verify(chain).nextCall(request);
    }

    @Test
    void adviseCall_isNullSafe_whenChatResponseMissing() {
        when(chain.nextCall(any())).thenReturn(response);
        when(response.chatResponse()).thenReturn(null);

        ChatClientResponse actual = newAdvisor().adviseCall(request, chain);

        assertThat(actual).isSameAs(response);
    }

    @Test
    void adviseCall_isNullSafe_whenMetadataMissing() {
        when(chain.nextCall(any())).thenReturn(response);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(null);

        ChatClientResponse actual = newAdvisor().adviseCall(request, chain);

        assertThat(actual).isSameAs(response);
    }

    @Test
    void adviseCall_isNullSafe_whenUsageMissing() {
        when(chain.nextCall(any())).thenReturn(response);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(null);

        ChatClientResponse actual = newAdvisor().adviseCall(request, chain);

        assertThat(actual).isSameAs(response);
    }

    @Test
    void advisorMetadata_orderIsHighEnoughForOutermostMeasurement() {
        PerformanceLoggingAdvisor advisor = newAdvisor();

        assertThat(advisor.getName()).isEqualTo("PerformanceLoggingAdvisor");
        assertThat(advisor.getOrder()).isEqualTo(100);
    }

    @Test
    void adviseCall_rethrowsOriginalException_whenChainThrows() {
        RuntimeException boom = new IllegalStateException("LLM down");
        when(chain.nextCall(request)).thenThrow(boom);

        PerformanceLoggingAdvisor advisor = newAdvisor();

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isSameAs(boom);
        verify(chain).nextCall(request);
    }

    private PerformanceLoggingAdvisor newAdvisor() {
        return new PerformanceLoggingAdvisor(new AgentMetrics(new SimpleMeterRegistry()));
    }
}
