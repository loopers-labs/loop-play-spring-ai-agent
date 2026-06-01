package com.baedal.support.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 3주차 — 세션 운영용 엔드포인트.
 * <p>
 * Memory가 "실제로 몇 개의 메시지를 들고 있는지"를 직접 관찰하기 위한 개발 전용 API.
 * 숙제의 시나리오 검증에서 이 엔드포인트로 Memory 상태를 캡처해 README에 붙인다.
 *
 * <p>프로덕션에서는 관리자 전용 엔드포인트로 분리하고 인증을 걸어야 한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/session")
public class SessionController {

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    // 세션에 저장된 메시지 목록을 반환한다.
    // 같은 세션 1턴 대화 후 호출하면 USER 1건 + ASSISTANT 1건 저장된 것을 볼 수 있다.
    @GetMapping("/{sessionId}/messages")
    public List<MessageView> messages(@PathVariable String sessionId) {
        return chatMemory.get(sessionId).stream()
                .map(MessageView::from)
                .toList();
    }

    // 세션을 비운다. clear 후 "그거" 질문은 맥락을 못 찾는다.
    @DeleteMapping("/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        chatMemory.clear(sessionId);
        log.info("[Session] clear sessionId={}", sessionId);
    }

    // Repository에 등록된 모든 세션 ID를 반환한다 (세션 분리 확인용).
    @GetMapping("/ids")
    public List<String> sessions() {
        return chatMemoryRepository.findConversationIds();
    }

    public record MessageView(String type, String content) {
        static MessageView from(Message m) {
            return new MessageView(m.getMessageType().name(), m.getText());
        }
    }
}
