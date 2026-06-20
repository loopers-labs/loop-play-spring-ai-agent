package com.baedal.assistant;

import com.baedal.assistant.tool.OrderTools;
import com.baedal.support.ChatRequest;
import com.baedal.support.PerformanceLoggingAdvisor;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.HandoffDetector.HandoffResult;
import com.baedal.support.guardrail.HandoffDetector.Trigger;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.observability.AgentMetrics;
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
    @Mock InputGuardrailAdvisor inputGuardrail;
    @Mock MessageChatMemoryAdvisor memoryAdvisor;
    @Mock QuestionAnswerAdvisor ragAdvisor;
    @Mock OutputGuardrailAdvisor outputGuardrail;
    @Mock PerformanceLoggingAdvisor advisor;
    @Mock HandoffDetector handoffDetector;
    @Mock AgentMetrics metrics;
    @Mock OrderTools orderTools;

    private void wireChain() {
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(handoffDetector.detect(anyString())).thenReturn(new HandoffResult(false, Trigger.NONE, null));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
    }

    @Test
    void ask_appliesSystemPromptAdvisorAndTools_thenReturnsContent() {
        wireChain();
        when(callSpec.content()).thenReturn("역삼역 사거리 부근입니다.");

        AssistantController controller = new AssistantController(builder, inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor, handoffDetector, metrics, orderTools);
        String result = controller.ask(new ChatRequest("주문번호 2024-1234 어디예요?"), "cust-A");

        assertThat(result).isEqualTo("역삼역 사거리 부근입니다.");
        verify(builder).defaultSystem(AssistantPrompt.SYSTEM_PROMPT);
        verify(builder).defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor);
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

        AssistantController controller = new AssistantController(builder, inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor, handoffDetector, metrics, orderTools);
        controller.ask(new ChatRequest("문의1"), "cust-A");
        controller.ask(new ChatRequest("문의2"), "cust-A");

        verify(builder, times(1)).build();
        verify(builder, times(1)).defaultTools(orderTools);
        verify(builder, times(1)).defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor);
        verify(builder, times(1)).defaultSystem(AssistantPrompt.SYSTEM_PROMPT);
    }

    @Test
    void ask_handoffRequest_shortCircuitsWithoutCallingLlm() {
        // Round 5 3단계: 상담원 전환은 LLM 호출 전에 가로채 모델을 부르지 않는다.
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(handoffDetector.detect("상담원이랑 직접 얘기하고 싶어요"))
                .thenReturn(new HandoffResult(true, Trigger.EXPLICIT_REQUEST,
                        "상담원 연결을 도와드리겠습니다. 연결이 지연되면 고객센터 1600-0987로 전화 주세요."));

        AssistantController controller = new AssistantController(builder, inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor, handoffDetector, metrics, orderTools);
        String result = controller.ask(new ChatRequest("상담원이랑 직접 얘기하고 싶어요"), "cust-A");

        assertThat(result).contains("1600-0987");
        verify(chatClient, never()).prompt();
        verify(metrics).handoff(Trigger.EXPLICIT_REQUEST.name());
    }

    @Test
    void ask_whenChainThrows_returnsSafeFallbackWithoutStackTrace() {
        // Round 5 4단계: LLM/Tool 실패가 예외로 올라오면 fallback이 받아 안전 응답을 돌려준다.
        wireChain();
        when(callSpec.content()).thenThrow(new RuntimeException("simulated LLM failure: connection refused at line 42"));

        AssistantController controller = new AssistantController(builder, inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor, handoffDetector, metrics, orderTools);
        String result = controller.ask(new ChatRequest("주문번호 2024-1234 상태 알려줘"), "cust-A");

        assertThat(result).contains("1600-0987");
        assertThat(result).doesNotContain("simulated LLM failure");
        assertThat(result).doesNotContain("line 42");
        verify(metrics).fallback();
    }
}
