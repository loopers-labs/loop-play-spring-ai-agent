package com.baedal.support.config;

import com.baedal.support.advisor.PerformanceLoggingAdvisor;
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
                                          MessageChatMemoryAdvisor memoryAdvisor,
                                          QuestionAnswerAdvisor ragAdvisor,
                                          PerformanceLoggingAdvisor performanceAdvisor,
                                          OrderTools orderTools) {
        // memoryAdvisor(order=10) → ragAdvisor(order=20) → performanceAdvisor(order=100) 순서.
        // memoryAdvisor 가 이전 대화 이력을 주입해 "아까 그 주문"을 복원한 뒤, ragAdvisor 가
        // 그 복원된 질문을 임베딩해 정책 문서를 검색·주입한다. performance 는 가장 바깥에서
        // 완성된 프롬프트의 토큰·응답 시간을 집계한다. (등록 순서가 아닌 각 advisor 의 order 가 실행순서 결정)
        // 세션별 conversationId 는 AssistantController 에서 호출 단위로 .advisors(...) 주입.
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }
}
