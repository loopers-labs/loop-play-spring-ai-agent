package com.baedal.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class SessionControllerTest {

    private final ChatMemoryConfig config = new ChatMemoryConfig();
    private final ChatMemoryRepository repository = config.chatMemoryRepository();
    private final ChatMemory memory = config.chatMemory(repository, ChatMemoryConfig.DEFAULT_MAX_MESSAGES);
    private final SessionController controller = new SessionController(memory, repository);

    @Test
    void messages_returnsStoredMessageViews() {
        memory.add("cust-A", new UserMessage("2024-1234 어디쯤이에요?"));
        memory.add("cust-A", new AssistantMessage("2024-1234 주문은 배달 중입니다."));

        assertThat(controller.messages("cust-A"))
                .extracting(SessionController.ChatMessageView::type)
                .containsExactly("user", "assistant");
        assertThat(controller.messages("cust-A"))
                .extracting(SessionController.ChatMessageView::text)
                .containsExactly("2024-1234 어디쯤이에요?", "2024-1234 주문은 배달 중입니다.");
    }

    @Test
    void clear_removesOnlyTargetSession() {
        memory.add("cust-A", new UserMessage("2024-1234"));
        memory.add("cust-B", new UserMessage("2024-1235"));

        controller.clear("cust-A");

        assertThat(controller.messages("cust-A")).isEmpty();
        assertThat(controller.messages("cust-B")).hasSize(1);
        assertThat(controller.ids()).containsExactly("cust-B");
    }
}
