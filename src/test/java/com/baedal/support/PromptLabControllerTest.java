package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptLabControllerTest {

    @Mock ChatClient.Builder builder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;

    private SupportResponse stub(SupportResponse.Category category) {
        return new SupportResponse(
                "s", category, SupportResponse.Urgency.NORMAL,
                "n", List.of(), null, SupportResponse.Confidence.MEDIUM
        );
    }

    private void wireChain() {
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
    }

    @Test
    void experiment_callsLlmRepeatTimes_andBuildsChatClientOnce() {
        wireChain();
        when(callSpec.entity(SupportResponse.class)).thenReturn(stub(SupportResponse.Category.ORDER));

        PromptLabController controller = new PromptLabController(builder);
        var req = new PromptLabController.PromptLabRequest("system", "user-msg", 3);

        var result = controller.experiment(req);

        verify(builder, times(1)).build();
        verify(callSpec, times(3)).entity(SupportResponse.class);
        assertThat(result.totalRuns()).isEqualTo(3);
        assertThat(result.categoryConsistency()).isEqualTo(1.0);
    }

    @Test
    void experiment_categoryConsistency_isMaxCountOverTotal_whenResponsesDiffer() {
        wireChain();
        when(callSpec.entity(SupportResponse.class)).thenReturn(
                stub(SupportResponse.Category.ORDER),
                stub(SupportResponse.Category.ORDER),
                stub(SupportResponse.Category.ETC)
        );

        PromptLabController controller = new PromptLabController(builder);
        var result = controller.experiment(
                new PromptLabController.PromptLabRequest("system", "msg", 3));

        assertThat(result.categoryCounts())
                .containsEntry("ORDER", 2L)
                .containsEntry("ETC", 1L);
        assertThat(result.categoryConsistency()).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void experiment_repeatZero_doesNotCallLlm_andReturnsEmptyStats() {
        PromptLabController controller = new PromptLabController(builder);

        var result = controller.experiment(
                new PromptLabController.PromptLabRequest("system", "msg", 0));

        verifyNoInteractions(builder);
        assertThat(result.totalRuns()).isZero();
        assertThat(result.categoryConsistency()).isZero();
        assertThat(result.categoryCounts()).isEmpty();
    }

    @Test
    void experiment_negativeRepeat_isTreatedAsZero() {
        PromptLabController controller = new PromptLabController(builder);

        var result = controller.experiment(
                new PromptLabController.PromptLabRequest("system", "msg", -5));

        verifyNoInteractions(builder);
        assertThat(result.totalRuns()).isZero();
    }
}
