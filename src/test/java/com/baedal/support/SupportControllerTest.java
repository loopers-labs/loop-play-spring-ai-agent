package com.baedal.support;

import com.baedal.assistant.tool.OrderTools;
import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.HandoffDetector.HandoffResult;
import com.baedal.support.guardrail.HandoffDetector.Trigger;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;

import java.util.List;
import java.util.function.Consumer;

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
    @Mock InputGuardrailAdvisor inputGuardrail;
    @Mock MessageChatMemoryAdvisor memoryAdvisor;
    @Mock QuestionAnswerAdvisor ragAdvisor;
    @Mock OutputGuardrailAdvisor outputGuardrail;
    @Mock PerformanceLoggingAdvisor advisor;
    @Mock HandoffDetector handoffDetector;
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

        SupportController controller = new SupportController(builder, inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor, handoffDetector, orderTools);
        SupportResponse result = controller.triage(new ChatRequest("주문 취소하고 싶어요"), "cust-A");

        assertThat(result.category()).isEqualTo(SupportResponse.Category.ORDER);
        assertThat(result.summary()).isEqualTo("주문 취소 요청");
        assertThat(result.confidenceLevel()).isEqualTo(SupportResponse.Confidence.HIGH);
        verify(builder).defaultSystem(BaedalPrompt.SYSTEM_PROMPT);
        verify(builder).defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor);
        verify(builder).defaultTools(orderTools);
        verify(requestSpec).user("주문 취소하고 싶어요");
        verify(requestSpec).advisors(any(Consumer.class));
    }

    @Test
    void triage_doesNotBuildChatClientPerRequest() {
        wireChain();
        when(callSpec.entity(SupportResponse.class)).thenReturn(
                new SupportResponse("s", SupportResponse.Category.ETC, SupportResponse.Urgency.LOW,
                        "n", List.of(), null, SupportResponse.Confidence.LOW)
        );

        SupportController controller = new SupportController(builder, inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor, handoffDetector, orderTools);
        controller.triage(new ChatRequest("문의1"), "cust-A");
        controller.triage(new ChatRequest("문의2"), "cust-A");

        verify(builder, times(1)).build();
        verify(builder, times(1)).defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor);
        verify(builder, times(1)).defaultTools(orderTools);
    }

    @Test
    void triage_handoff_assemblesEtcHighResponseWithoutLlm() {
        // Round 5 3단계: support는 전환 시 SupportResponse를 수동 조립한다 (Category=ETC, Urgency=HIGH).
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(handoffDetector.detect("이거 너무 화나서 소비자원에 신고할 거예요"))
                .thenReturn(new HandoffResult(true, Trigger.LEGAL_ISSUE,
                        "관련 사안은 전문 상담원이 직접 안내드리겠습니다. 고객센터 1600-0987로 연결해 드리겠습니다."));

        SupportController controller = new SupportController(builder, inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, advisor, handoffDetector, orderTools);
        SupportResponse result = controller.triage(new ChatRequest("이거 너무 화나서 소비자원에 신고할 거예요"), "cust-A");

        assertThat(result.category()).isEqualTo(SupportResponse.Category.ETC);
        assertThat(result.urgency()).isEqualTo(SupportResponse.Urgency.HIGH);
        assertThat(result.nextAction()).isEqualTo("상담원 연결 진행");
        assertThat(result.summary()).contains("1600-0987");
        verify(chatClient, never()).prompt();
    }
}
