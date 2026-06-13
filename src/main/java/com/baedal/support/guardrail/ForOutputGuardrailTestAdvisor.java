package com.baedal.support.guardrail;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
public class ForOutputGuardrailTestAdvisor implements CallAdvisor {
    private final String fakeResponse;

    public ForOutputGuardrailTestAdvisor(String fakeResponse) {
        this.fakeResponse = fakeResponse;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 실제 LLM(chain.nextCall)을 호출하지 않고, 테스트용 응답을 바로 만들어서 반환
        AssistantMessage message = new AssistantMessage(fakeResponse);
        Generation generation = new Generation(message);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(generation))
                .build();

        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    @Override
    public String getName() {
        return "ForOutputGuardrailTestAdvisor";
    }

    @Override
    public int getOrder() {
        return 150;
    }
}
