package com.baedal.support.controller;

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

    /** 세션의 누적 메시지 목록 — 시나리오 검증 (USER × N + ASSISTANT × N 순서). */
    @GetMapping("/{sessionId}/messages")
    public List<MessageView> messages(@PathVariable String sessionId) {
        return chatMemory.get(sessionId).stream()
                .map(MessageView::from)
                .toList();
    }

    /** 세션 클리어 — 시나리오 4 (clear 후 "그거" 질문이 맥락 못 찾는지 검증). */
    @DeleteMapping("/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        chatMemory.clear(sessionId);
        log.info("[Session] clear sessionId={}", sessionId);
    }

    /** 등록된 세션 ID 전체 — 시나리오 3 (세션 분리 검증, 평가축 ★). */
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
