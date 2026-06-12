package com.baedal.support.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 3주차 — 세션 운영용 엔드포인트.
 * <p>
 * Memory가 "실제로 몇 개의 메시지를 들고 있는지"를 직접 관찰하기 위한 개발 전용 API.
 * 숙제의 시나리오 검증에서 이 엔드포인트로 Memory 상태를 캡처해 README에 붙인다.
 *
 * <p>프로덕션에서는 관리자 전용 엔드포인트로 분리하고 인증을 걸어야 한다.

 * 수업 중 라이브 데모와 숙제에서 Memory의 상태를 직접 관찰하기 위한 개발 전용 API다.
 * 프로덕션에서는 관리자 전용 엔드포인트로 분리하고 인증을 걸어야 한다.
>>>>>>> upstream/round4
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/session")
public class SessionController {

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    /**
     * 특정 세션의 현재 저장된 메시지 목록을 조회한다.
     * Memory가 실제로 몇 개 메시지를 들고 있는지, 어떤 내용인지 눈으로 확인할 때 사용.
     */
    @GetMapping("/{sessionId}/messages")
    public List<MessageView> messages(@PathVariable String sessionId) {
        return chatMemory.get(sessionId).stream()
                .map(MessageView::from)
                .toList();
    }

    /**
     * 특정 세션의 Memory를 비운다.
     * 라이브 데모에서 "세션 오염" 이후 깔끔하게 리셋할 때 사용.
     */
    @DeleteMapping("/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        log.info("[Session] clear sessionId={}", sessionId);
        chatMemory.clear(sessionId);
    }

    /**
     * 현재 Repository에 등록된 모든 세션 ID를 조회한다.
     * 멀티 세션 데모에서 "두 세션이 실제로 분리되어 저장되고 있다"는 걸 보여줄 때 사용.
     */
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
