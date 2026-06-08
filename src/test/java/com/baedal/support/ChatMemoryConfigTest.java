package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryConfigTest {

    private final ChatMemoryConfig config = new ChatMemoryConfig();

    @Test
    void messageWindowChatMemory_keepsRecentMessagesWithinConfiguredLimit() {
        ChatMemoryRepository repository = config.chatMemoryRepository();
        ChatMemory memory = config.chatMemory(repository, ChatMemoryConfig.DEFAULT_MAX_MESSAGES);

        IntStream.rangeClosed(1, ChatMemoryConfig.DEFAULT_MAX_MESSAGES + 1)
                .mapToObj(i -> new UserMessage("message-" + i))
                .forEach(message -> memory.add("cust-A", message));

        assertThat(memory.get("cust-A"))
                .hasSize(ChatMemoryConfig.DEFAULT_MAX_MESSAGES)
                .extracting(message -> message.getText())
                .doesNotContain("message-1")
                .contains("message-21");
    }

    @Test
    void messageWindowChatMemory_usesInjectedMaxMessages() {
        ChatMemoryRepository repository = config.chatMemoryRepository();
        ChatMemory memory = config.chatMemory(repository, 2);

        memory.add("cust-A", new UserMessage("message-1"));
        memory.add("cust-A", new UserMessage("message-2"));
        memory.add("cust-A", new UserMessage("message-3"));

        assertThat(memory.get("cust-A"))
                .hasSize(2)
                .extracting(message -> message.getText())
                .containsExactly("message-2", "message-3");
    }

    @Test
    void repository_separatesConversationIds() {
        ChatMemoryRepository repository = config.chatMemoryRepository();
        ChatMemory memory = config.chatMemory(repository, ChatMemoryConfig.DEFAULT_MAX_MESSAGES);

        memory.add("cust-A", new UserMessage("2024-1234 어디쯤이에요?"));
        memory.add("cust-B", new UserMessage("그 주문 어디쯤이에요?"));

        assertThat(repository.findConversationIds()).containsExactlyInAnyOrder("cust-A", "cust-B");
        assertThat(memory.get("cust-A")).extracting(message -> message.getText())
                .containsExactly("2024-1234 어디쯤이에요?");
        assertThat(memory.get("cust-B")).extracting(message -> message.getText())
                .containsExactly("그 주문 어디쯤이에요?");
    }
}
