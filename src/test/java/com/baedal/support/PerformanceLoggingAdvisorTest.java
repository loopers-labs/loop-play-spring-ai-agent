package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceLoggingAdvisorTest {

    private PerformanceLoggingAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new PerformanceLoggingAdvisor();
    }

    @Test
    void adviseCall_정상응답_응답반환() {
        var promptTokens = 100;
        var completionTokens = 50;
        var totalTokens = 150;
        var mockResponse = mockResponseWithUsage(promptTokens, completionTokens, totalTokens);
        var mockChain = mockChainReturning(mockResponse);

        var result = advisor.adviseCall(mock(ChatClientRequest.class), mockChain);

        assertThat(result).isEqualTo(mockResponse);
    }

    @Test
    void adviseCall_usageNull_NPE없이동작() {
        var mockResponse = mockResponseWithNullUsage();
        var mockChain = mockChainReturning(mockResponse);

        assertThatCode(() -> advisor.adviseCall(mock(ChatClientRequest.class), mockChain))
                .doesNotThrowAnyException();
    }

    @Test
    void adviseCall_LLM예외발생_예외재전파() {
        var mockChain = mock(CallAdvisorChain.class);
        when(mockChain.nextCall(any())).thenThrow(new RuntimeException("LLM 오류"));

        assertThatThrownBy(() -> advisor.adviseCall(mock(ChatClientRequest.class), mockChain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("LLM 오류");
    }

    @Test
    void adviseCall_로깅예외발생시_응답정상반환() {
        var mockResponse = mock(ChatClientResponse.class);
        when(mockResponse.chatResponse()).thenThrow(new RuntimeException("메타데이터 파싱 실패"));
        var mockChain = mockChainReturning(mockResponse);

        var result = advisor.adviseCall(mock(ChatClientRequest.class), mockChain);

        assertThat(result).isEqualTo(mockResponse);
    }

    // --- Fixture helpers ---

    private ChatClientResponse mockResponseWithUsage(int promptTokens, int completionTokens, int totalTokens) {
        var mockUsage = mock(Usage.class);
        when(mockUsage.getPromptTokens()).thenReturn(promptTokens);
        when(mockUsage.getCompletionTokens()).thenReturn(completionTokens);
        when(mockUsage.getTotalTokens()).thenReturn(totalTokens);

        var mockMetadata = mock(ChatResponseMetadata.class);
        when(mockMetadata.getUsage()).thenReturn(mockUsage);

        var mockChatResponse = mock(ChatResponse.class);
        when(mockChatResponse.getMetadata()).thenReturn(mockMetadata);

        var mockResponse = mock(ChatClientResponse.class);
        when(mockResponse.chatResponse()).thenReturn(mockChatResponse);

        return mockResponse;
    }

    private ChatClientResponse mockResponseWithNullUsage() {
        var mockMetadata = mock(ChatResponseMetadata.class);
        when(mockMetadata.getUsage()).thenReturn(null);

        var mockChatResponse = mock(ChatResponse.class);
        when(mockChatResponse.getMetadata()).thenReturn(mockMetadata);

        var mockResponse = mock(ChatClientResponse.class);
        when(mockResponse.chatResponse()).thenReturn(mockChatResponse);

        return mockResponse;
    }

    private CallAdvisorChain mockChainReturning(ChatClientResponse response) {
        var mockChain = mock(CallAdvisorChain.class);
        when(mockChain.nextCall(any())).thenReturn(response);
        return mockChain;
    }
}
