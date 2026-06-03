package com.baedal.support.memory;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 3주차 — Chat Memory 설정.
 *
 * <h3>구성 요소 (3레이어)</h3>
 * <ul>
 *     <li>{@link ChatMemoryRepository} : 메시지의 저장소 (CRUD) — JPA Repository에 대응</li>
 *     <li>{@link ChatMemory}           : 저장소 위에 "크기 제어 정책"을 얹은 것 — Service 계층에 대응</li>
 *     <li>{@link MessageChatMemoryAdvisor} : ChatClient 호출 흐름에 Memory를 연결하는 어댑터 — 인터셉터에 대응</li>
 * </ul>
 *
 * <p>세 Bean이 모두 등록되어야 Memory가 "자동으로" 동작한다.
 * 학생은 각 Bean을 구현하면서 <b>왜 이 값/전략을 선택했는지</b>를 README에 기록해야 한다.
 *
 * @see SessionController     Memory 상태 확인용 엔드포인트
 * @see JdbcChatMemoryExample JDBC 저장소로 전환하는 방법 (3단계 숙제)
 */
@Configuration
public class ChatMemoryConfig {

    //슬라이딩 윈도우 크기 — 한 세션에서 유지할 최근 메시지 개수.
    private static final int MAX_MESSAGES = 20;

    // 메시지 저장소 — 메모리 기반 CRUD : InMemoryChatMemoryRepository는 프로세스 메모리(맵)에 대화를 보관한다.
    @Bean
    @Profile("!jdbc")   // jdbc 프로필에서는 자동 구성된 JdbcChatMemoryRepository가 주입되도록 InMemory 빈을 끈다
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    //크기 제어 정책 - MessageWindowChatMemory는 저장소 위에 "최근 N(MAX_MESSAGES)개만 유지하는 슬라이딩 윈도우" 정책을 얹는다.
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(MAX_MESSAGES)
                .build();
    }

    //어댑터 — ChatClient 호출 흐름에 Memory를 연결.
    // MessageChatMemoryAdvisor는 ChatClient가 메시지를 주고받을 때 Memory에서 대화 이력을 조회/저장하도록 연결한다.
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .order(10)
                .build();
    }
}
