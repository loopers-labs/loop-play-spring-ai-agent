package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportControllerTest {

    @Mock ChatClient.Builder builder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock PerformanceLoggingAdvisor advisor;

    private void wireChain() {
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
    }

    @Test
    void triage_appliesSystemPromptAndReturnsStructuredResponse() {
        SupportResponse stub = new SupportResponse(
                "주문 취소 요청",
                SupportResponse.Category.ORDER,
                SupportResponse.Urgency.NORMAL,
                "주문번호 확인 후 취소 처리 안내",
                List.of("주문번호"),
                5,
                SupportResponse.Confidence.HIGH
        );
        wireChain();
        when(callSpec.entity(SupportResponse.class)).thenReturn(stub);

        SupportController controller = new SupportController(builder, advisor);
        SupportResponse result = controller.triage(new ChatRequest("주문 취소하고 싶어요"));

        assertThat(result.category()).isEqualTo(SupportResponse.Category.ORDER);
        assertThat(result.summary()).isEqualTo("주문 취소 요청");
        assertThat(result.confidenceLevel()).isEqualTo(SupportResponse.Confidence.HIGH);
        verify(builder).defaultSystem(BaedalPrompt.SYSTEM_PROMPT);
        verify(builder).defaultAdvisors(advisor);
        verify(requestSpec).user("주문 취소하고 싶어요");
    }

    @Test
    void triage_doesNotBuildChatClientPerRequest() {
        wireChain();
        when(callSpec.entity(SupportResponse.class)).thenReturn(
                new SupportResponse("s", SupportResponse.Category.ETC, SupportResponse.Urgency.LOW,
                        "n", List.of(), null, SupportResponse.Confidence.LOW)
        );

        SupportController controller = new SupportController(builder, advisor);
        controller.triage(new ChatRequest("문의1"));
        controller.triage(new ChatRequest("문의2"));

        verify(builder, times(1)).build();
        verify(builder, times(1)).defaultAdvisors(advisor);
    }
}
