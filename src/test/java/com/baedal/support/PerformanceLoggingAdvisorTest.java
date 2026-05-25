package com.baedal.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceLoggingAdvisorTest {

    @Mock
    private PerCallObservationHandler mockHandler;

    private PerformanceLoggingAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new PerformanceLoggingAdvisor(mockHandler);
    }

    @Test
    void adviseCall_정상응답_응답반환() {
        when(mockHandler.getCallStats()).thenReturn(List.of(
                new long[]{520, 12, 1200},
                new long[]{610, 43, 950}
        ));
        var mockResponse = mock(ChatClientResponse.class);
        var mockChain = mockChainReturning(mockResponse);

        var result = advisor.adviseCall(mock(ChatClientRequest.class), mockChain);

        assertThat(result).isEqualTo(mockResponse);
    }

    @Test
    void adviseCall_Tool호출없는_단순응답_정상동작() {
        when(mockHandler.getCallStats()).thenReturn(List.of(
                new long[]{32, 12, 449}
        ));
        var mockResponse = mock(ChatClientResponse.class);
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
        when(mockHandler.getCallStats()).thenThrow(new RuntimeException("상태 조회 실패"));
        var mockResponse = mock(ChatClientResponse.class);
        var mockChain = mockChainReturning(mockResponse);

        var result = advisor.adviseCall(mock(ChatClientRequest.class), mockChain);

        assertThat(result).isEqualTo(mockResponse);
    }

    // --- Fixture helpers ---

    private CallAdvisorChain mockChainReturning(ChatClientResponse response) {
        var mockChain = mock(CallAdvisorChain.class);
        when(mockChain.nextCall(any())).thenReturn(response);
        return mockChain;
    }
}
