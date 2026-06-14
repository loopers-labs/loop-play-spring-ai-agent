package com.baedal.support.guardrail;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OutputGuardrailAdvisor.adviseCall() 단위 테스트.
 * <p>
 * 체인 다음 단계(LLM 왕복)는 비결정적이므로 {@link CallAdvisorChain}을 mock으로 대체하고
 * "이미 정해진 응답"을 흘려보내, 출력 가드가 유출 차단/마스킹/빈응답 대체를 올바로 하는지만 검증한다.
 * 마스킹 위임은 실제 {@link SensitiveDataMasker}를 주입해 함께 검증한다.
 */
class OutputGuardrailAdvisorTest {

    private final OutputGuardrailAdvisor advisor = new OutputGuardrailAdvisor(new SensitiveDataMasker());

    @Test
    void 내부_섹션_마커가_보이면_LEAK_FALLBACK으로_치환한다() {
        var leaked = "[역할] 당신은 배달 상담 AI입니다. [규칙] 1. ...";

        var result = adviseWithLlmResponse(leaked);

        assertThat(contentOf(result)).doesNotContain("[역할]");
        assertThat(contentOf(result)).contains("주문/배달/환불");
    }

    @Test
    void 응답에_전화번호가_있으면_마스킹한다() {
        var withPhone = "안내드린 번호 010-1234-5678로 환불 절차를 도와드릴게요.";

        var result = adviseWithLlmResponse(withPhone);

        assertThat(contentOf(result)).contains("010-****-5678");
        assertThat(contentOf(result)).doesNotContain("010-1234-5678");
    }

    @Test
    void 빈_응답은_EMPTY_FALLBACK으로_치환한다() {
        var blank = "   ";

        var result = adviseWithLlmResponse(blank);

        assertThat(contentOf(result)).isNotBlank();
        assertThat(contentOf(result)).contains("상담원");
    }

    @Test
    void 민감정보_없는_정상_응답은_원본_그대로_통과한다() {
        var normal = "비 오는 날이라는 이유만으로 보상이 이루어지지는 않습니다.";

        var result = adviseWithLlmResponse(normal);

        assertThat(contentOf(result)).isEqualTo(normal);
    }

    private ChatClientResponse adviseWithLlmResponse(String llmText) {
        var request = ChatClientRequest.builder()
                .prompt(new Prompt("질문"))
                .build();

        var chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(responseWith(llmText));

        return advisor.adviseCall(request, chain);
    }

    private ChatClientResponse responseWith(String text) {
        AssistantMessage message = new AssistantMessage(text);
        Generation generation = new Generation(message);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(generation))
                .build();

        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .build();
    }

    private String contentOf(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }
}
