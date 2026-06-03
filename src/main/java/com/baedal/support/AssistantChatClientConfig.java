package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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
                                          PerformanceLoggingAdvisor performanceAdvisor,
                                          OrderTools orderTools) {
        // TODO [1단계-H] memoryAdvisor를 default advisor 체인에 추가하라.
        //
        // 요구사항:
        //   - .defaultAdvisors(memoryAdvisor, performanceAdvisor) 순서로 등록
        //     (memoryAdvisor가 먼저 실행되어 대화 이력을 주입한 뒤 performance가 측정)
        //
        // 왜 여기서 등록하는가:
        //   - ChatClient는 빈으로 한 번만 조립되어 모든 세션이 공유한다.
        //   - 세션별 conversationId는 컨트롤러에서 호출 단위로 .advisors(a -> ...)로 주입한다.
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor) // TODO: memoryAdvisor를 첫 번째로 추가
                .defaultTools(orderTools)
                .build();
    }
}
