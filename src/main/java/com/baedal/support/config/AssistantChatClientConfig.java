package com.baedal.support.config;

import com.baedal.support.advisor.PerformanceLoggingAdvisor;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.prompt.BaedalPrompt;
import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 배달 상담 에이전트용 {@link ChatClient}를 한 번만 조립하여 빈으로 노출한다.
 * <p>
 * 왜 별도 Config로 빼는가:
 * {@link ChatClient.Builder}는 싱글톤 빈이다. 만약 컨트롤러에서 요청마다
 * {@code builder.defaultTools(...).defaultAdvisors(...).build()}를 호출하면
 * 동일한 Tool / Advisor가 같은 Builder에 누적되어 결국
 * {@code Multiple tools with the same name} 오류가 발생한다.
 * <p>
 * 따라서 ChatClient 조립은 애플리케이션 기동 시 한 번만 수행하고,
 * 컨트롤러는 완성된 {@link ChatClient}를 그대로 주입받아 사용한다.
 * <p>
 * 3주차 숙제에서 {@link MessageChatMemoryAdvisor}를 default advisor 체인에 추가해야 한다.
 */
@Configuration
public class AssistantChatClientConfig {

    @Bean
    public ChatClient assistantChatClient(ChatClient.Builder builder,
                                          InputGuardrailAdvisor inputGuardrail,
                                          MessageChatMemoryAdvisor memoryAdvisor,
                                          QuestionAnswerAdvisor ragAdvisor,
                                          OutputGuardrailAdvisor outputGuardrail,
                                          PerformanceLoggingAdvisor performanceAdvisor,
                                          OrderTools orderTools) {
        // Round 5 확정 체인 (각 advisor 의 order 가 실행순서 결정, 등록 순서 아님):
        //   inputGuardrail(5) → memory(10) → rag(20) → outputGuardrail(50) → performance(100)
        // inputGuardrail 이 맨 앞에서 공격/빈입력/장문을 short-circuit 하면 그 뒤 Memory·RAG·LLM 이
        // 아예 안 돌아 토큰 비용 0 + 공격 입력이 ChatMemory 에 적재되지 않는다.
        // 통과 시 memory 가 "아까 그 주문" 복원 → rag 가 임베딩 검색·주입 → (LLM) → outputGuardrail 이
        // 돌아오는 응답을 마스킹/유출차단 → performance 가 가장 바깥에서 "마스킹된" 토큰·시간 집계.
        // 세션별 conversationId 는 AssistantController 에서 호출 단위로 .advisors(...) 주입.
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }
}
