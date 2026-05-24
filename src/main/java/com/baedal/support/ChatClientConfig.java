package com.baedal.support;

import org.springframework.ai.chat.client.ChatClient;
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
     * /api/v1/support — BaedalPrompt + 토큰·지연(PerformanceLoggingAdvisor).
     * 요청·응답 본문 로깅(SimpleLoggerAdvisor)은 로컬·개발 프로파일에서만 추가된다.
     * (운영에서 본문 평문 로깅은 PII 유출·로그 비용·감사 부담 위험)
     */
    @Bean
    ChatClient supportChatClient(ChatClient.Builder builder,
                                 PerformanceLoggingAdvisor performanceAdvisor,
                                 ObjectProvider<SimpleLoggerAdvisor> simpleLoggerOpt) {
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(performanceAdvisor);
        simpleLoggerOpt.ifAvailable(advisors::add);   // 빈이 등록된 프로파일에서만 추가
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(advisors.toArray(new Advisor[0]))
                .build();
    }

    /**
     * 요청·응답 본문을 DEBUG 로그로 남기는 디버그용 advisor.
     * 운영에는 PII 유출·로그 비용 때문에 등록하지 않는다 — 로컬·개발 프로파일 한정.
     * 활성화: 실행 시 -Dspring.profiles.active=local (또는 dev)
     */
    @Bean
    @Profile({"local", "dev"})
    SimpleLoggerAdvisor simpleLoggerAdvisor() {
        return new SimpleLoggerAdvisor();
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
