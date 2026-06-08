package com.baedal.assistant;

import com.baedal.assistant.tool.OrderTools;
import com.baedal.support.ChatRequest;
import com.baedal.support.PerformanceLoggingAdvisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantControllerTest {

    @Mock ChatClient.Builder builder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock MessageChatMemoryAdvisor memoryAdvisor;
    @Mock QuestionAnswerAdvisor ragAdvisor;
    @Mock PerformanceLoggingAdvisor advisor;
    @Mock OrderTools orderTools;

    private void wireChain() {
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
    }

    @Test
    void ask_appliesSystemPromptAdvisorAndTools_thenReturnsContent() {
        wireChain();
        when(callSpec.content()).thenReturn("역삼역 사거리 부근입니다.");

        AssistantController controller = new AssistantController(builder, memoryAdvisor, ragAdvisor, advisor, orderTools);
        String result = controller.ask(new ChatRequest("주문번호 2024-1234 어디예요?"), "cust-A");

        assertThat(result).isEqualTo("역삼역 사거리 부근입니다.");
        verify(builder).defaultSystem(AssistantPrompt.SYSTEM_PROMPT);
        verify(builder).defaultAdvisors(memoryAdvisor, ragAdvisor, advisor);
        verify(builder).defaultTools(orderTools);
        verify(requestSpec).user("주문번호 2024-1234 어디예요?");
        verify(requestSpec).advisors(any(Consumer.class));
    }

    @Test
    void ask_doesNotBuildChatClientPerRequest_andDoesNotReRegisterTools() {
        // 단계 1 회고에서 명시한 "ChatClient.Builder 싱글톤에 .defaultTools를 매 요청마다 호출하면
        // Multiple tools with the same name 트랩이 터진다"는 자기 약속을 테스트로 박는다.
        wireChain();
        when(callSpec.content()).thenReturn("ok");

        AssistantController controller = new AssistantController(builder, memoryAdvisor, ragAdvisor, advisor, orderTools);
        controller.ask(new ChatRequest("문의1"), "cust-A");
        controller.ask(new ChatRequest("문의2"), "cust-A");

        verify(builder, times(1)).build();
        verify(builder, times(1)).defaultTools(orderTools);
        verify(builder, times(1)).defaultAdvisors(memoryAdvisor, ragAdvisor, advisor);
        verify(builder, times(1)).defaultSystem(AssistantPrompt.SYSTEM_PROMPT);
    }
}
