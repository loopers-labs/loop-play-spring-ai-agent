package com.baedal.support;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository repository;

    public SessionController(ChatMemory chatMemory, ChatMemoryRepository repository) {
        this.chatMemory = chatMemory;
        this.repository = repository;
    }

    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageView> messages(@PathVariable String sessionId) {
        return chatMemory.get(sessionId).stream()
                .map(ChatMessageView::from)
                .toList();
    }

    @DeleteMapping("/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        chatMemory.clear(sessionId);
    }

    @GetMapping("/ids")
    public List<String> ids() {
        return repository.findConversationIds();
    }

    public record ChatMessageView(String type, String text) {
        static ChatMessageView from(Message message) {
            return new ChatMessageView(message.getMessageType().getValue(), message.getText());
        }
    }
}
