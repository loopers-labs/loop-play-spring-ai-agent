package com.baedal.support.memory;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 3주차 — ChatMemory 3레이어 조립.
 * <p>
 * 레이어 구조 (아래에서 위로):
 * <ol>
 *   <li><b>{@link ChatMemoryRepository}</b> — 저장소. 어디에 보관하는가(InMemory / JDBC).</li>
 *   <li><b>{@link ChatMemory}</b> — 윈도 정책. 몇 개까지 들고 가는가({@link MessageWindowChatMemory}).</li>
 *   <li><b>{@link MessageChatMemoryAdvisor}</b> — 주입기. 매 요청에 이력을 프롬프트에 끼워 넣는다.</li>
 * </ol>
 * 세션 분리는 호출 단위로 {@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))}
 * 로 주입한다(컨트롤러 책임). 이 Config는 "어떻게 저장·윈도잉·주입하는가"만 정의한다.
 */
@Configuration
public class ChatMemoryConfig {

    // [1단계-A] 한 세션이 LLM에 들고 가는 최대 메시지 수.
    // 너무 크면 입력 토큰·비용↑(매 요청 누적 전송), 너무 작으면 맥락이 끊긴다.
    // 권장 20 = USER/ASSISTANT 약 10턴. 지시 대명사("그거") 해소에 충분한 윈도.
    // 기본 20. 2단계 실험을 위해 chat.memory.max-messages 프로퍼티로 런타임 override 가능
    // (예: --chat.memory.max-messages=2 / =2147483647).
    public static final int MAX_MESSAGES = 20;

    // [1단계-B] 저장소 — InMemory(개발 기본).
    // @Profile("!jdbc"): jdbc 프로필에서는 이 빈을 만들지 않아,
    // 자동 구성된 JdbcChatMemoryRepository가 대신 주입된다. (JdbcChatMemoryExample 참조)
    @Bean
    @Profile("!jdbc")
    ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    // [1단계-C] 윈도 정책 — 최근 MAX_MESSAGES 개만 유지.
    // MessageWindowChatMemory가 저장소에 쌓인 이력 중 마지막 N개만 잘라 돌려준다.
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                          @Value("${chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }

    // [1단계-D] 주입기 — 매 요청 직전 이력을 프롬프트에 끼우고, 응답을 다시 저장한다.
    // order(10): 낮을수록 바깥(먼저 실행). 이력 주입이 PerformanceLoggingAdvisor 측정보다
    // 앞서도록 작은 order를 준다.
    @Bean
    MessageChatMemoryAdvisor memoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .order(10)
                .build();
    }
}
