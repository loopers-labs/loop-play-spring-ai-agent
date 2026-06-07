package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatClient 빈을 엔드포인트별로 한 번만 빌드해 재사용한다.
 * (이전: 컨트롤러마다 요청 시점에 Builder.build() 호출 → 매 요청 빌드 비용 / advisor 매번 new)
 *
 * 빈 분리 원칙: 엔드포인트 간 설정이 같더라도 빈을 분리해두면
 * 이후 advisor·옵션 추가가 서로 영향 없이 가능해진다.
 */
@Configuration
public class ChatClientConfig {

    /**
     * /api/v1/support — BaedalPrompt + 토큰·지연(PerformanceLoggingAdvisor) + OrderTools.
     * 요청·응답 본문 로깅(SimpleLoggerAdvisor)은 로컬·개발 프로파일에서만 추가된다.
     * (운영에서 본문 평문 로깅은 PII 유출·로그 비용·감사 부담 위험)
     *
     * 2주차: OrderTools 등록 — 분류(Structured Output) + Tool Calling을 한 번에.
     */
    @Bean
    ChatClient supportChatClient(ChatClient.Builder builder,
                                 MessageChatMemoryAdvisor memoryAdvisor,
                                 PerformanceLoggingAdvisor performanceAdvisor,
                                 ObjectProvider<SimpleLoggerAdvisor> simpleLoggerOpt,
                                 OrderTools orderTools) {
        // 3주차: memoryAdvisor를 첫 번째로 — 세션 이력을 먼저 주입한 뒤 performance 측정.
        // 세션별 conversationId는 컨트롤러에서 호출 단위로 .advisors(a -> ...)로 주입한다.
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(memoryAdvisor);
        advisors.add(performanceAdvisor);
        simpleLoggerOpt.ifAvailable(advisors::add);   // 빈이 등록된 프로파일에서만 추가
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(advisors.toArray(new Advisor[0]))
                .defaultTools(orderTools)
                .build();
    }

    // 3주차: assistantChatClient 빈은 메모리 도입과 함께 AssistantChatClientConfig 로 이전됨.
    //        (같은 이름 빈 중복 정의를 피하기 위해 여기서는 제거)

    /**
     * 요청·응답 본문을 DEBUG 로그로 남기는 디버그용 advisor.
     * 운영에는 PII 유출·로그 비용 때문에 등록하지 않는다 — 로컬·개발 프로파일 한정.
     * 활성화: 실행 시 -Dspring.profiles.active=local (또는 dev)
     *
     * order(20): memoryAdvisor(order 10)보다 <b>큰</b> order — 즉 메모리 주입 '이후'에 실행된다.
     * Spring AI advisor 체인은 order 낮을수록 먼저(바깥) 실행되므로,
     * 기본 order(0)이면 SimpleLogger가 memory(10)보다 먼저 돌아 '주입 전' 프롬프트만 찍힌다
     * (request 로그 messages=[System, 현재 User]). order를 20으로 올려 memory 주입 후 로깅하면
     * 주입된 이전 대화 이력까지 DEBUG 로그에 보인다.
     */
    @Bean
    @Profile({"local", "dev"})
    SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return new SimpleLoggerAdvisor(20);
    }

    /** /api/v1/chat/stream — BaedalPrompt만 적용, advisor 없음(현 동작 유지). */
    @Bean
    ChatClient streamingChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .build();
    }

    /** /api/v1/chat — 시스템 프롬프트·advisor 없는 순수 챗(현 동작 유지). */
    @Bean
    ChatClient plainChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * /api/v1/prompt-lab — 시스템 프롬프트를 요청별로 주입해야 하므로
     * 빈에는 defaultSystem을 두지 않고, 호출 시 .prompt().system(...)로 오버라이드한다.
     * (이전: 매 요청 builder.defaultSystem(req.systemPrompt()).build())
     */
    @Bean
    ChatClient promptLabChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
