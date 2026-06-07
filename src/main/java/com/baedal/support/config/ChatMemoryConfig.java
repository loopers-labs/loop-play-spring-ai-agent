package com.baedal.support.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 3주차 — Chat Memory 설정 (3레이어).
 *
 * <ul>
 *     <li>{@link ChatMemoryRepository} : 메시지 저장소 (CRUD)</li>
 *     <li>{@link ChatMemory}           : 저장소 위의 크기 제어 정책 레이어</li>
 *     <li>{@link MessageChatMemoryAdvisor} : ChatClient 호출 흐름에 Memory 를 연결하는 어댑터</li>
 * </ul>
 *
 * <p>설계 근거(MAX_MESSAGES 선택, advisor order, InMemory↔JDBC 전환)는 {@code reports/week3} 보고서 참조.
 *
 * @see com.baedal.support.controller.SessionController Memory 상태 확인용 엔드포인트
 * @see JdbcChatMemoryExample                            JDBC 저장소로 전환하는 방법
 */
@Configuration
public class ChatMemoryConfig {

    // 슬라이딩 윈도우 크기. 배달 상담 평균 ~10턴(=20 메시지) 을 덮는 값.
    // 2단계 ablation(2 / 20 / Integer.MAX_VALUE) 결과: 2 는 먼 지시대명사 해결 실패,
    // 무제한은 입력 토큰 선형 증가 → 20 이 비용↔맥락 sweet spot. (stage2 보고서)
    private static final int MAX_MESSAGES = 20;

    // @Profile("!jdbc"): jdbc 프로필이 아닐 때만 InMemory 빈을 등록한다.
    // jdbc 프로필에서는 이 빈이 빠지고, 자동구성된 JdbcChatMemoryRepository 가 주입된다.
    // (h2 classpath 로 인한 자동구성 충돌은 application.yml 의 autoconfigure.exclude 로 차단 — stage3 보고서)
    @Bean
    @Profile("!jdbc")
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    // 최근 MAX_MESSAGES 개만 유지하는 윈도우 정책 (요약 전략과의 trade-off 는 stage2 보고서).
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(MAX_MESSAGES)
                .build();
    }

    // order(10): PerformanceLoggingAdvisor(order=100) 보다 먼저 실행되어 프롬프트 조립 시점에
    // 이전 대화를 주입한다. 그 뒤 performance 가 완성된 프롬프트의 토큰·시간을 집계한다.
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .order(10)
                .build();
    }
}
