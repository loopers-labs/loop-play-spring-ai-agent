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

    // TODO [1단계-E] 세션의 저장된 메시지 목록을 반환하라.
    //
    // 요구사항:
    //   - chatMemory.get(sessionId)로 메시지 리스트를 가져온다.
    //   - 각 Message를 MessageView(type, content)로 변환하여 반환한다.
    //     (MessageView.from(Message m) 가 이미 아래에 준비되어 있다.)
    //
    // 검증 포인트:
    //   - 같은 세션으로 두 번 대화한 뒤 이 엔드포인트 호출
    //     → USER 2건 + ASSISTANT 2건이 순서대로 보여야 한다.
    @GetMapping("/{sessionId}/messages")
    public List<MessageView> messages(@PathVariable String sessionId) {
        return chatMemory.get(sessionId).stream()
                .map(MessageView::from)
                .toList();
    }

    // TODO [1단계-F] 세션을 비우라.
    //
    // 요구사항:
    //   - chatMemory.clear(sessionId) 호출
    //   - log.info("[Session] clear sessionId={}", sessionId) 로 감사 로그를 남긴다.
    //
    // 검증 포인트 (1단계 시나리오 4번):
    //   - 2024-1234 대화 후 이 엔드포인트 호출 → "그거" 질문이 다시 맥락을 못 찾아야 한다.
    @DeleteMapping("/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        chatMemory.clear(sessionId);
        log.info("[Session] clear sessionId={}", sessionId);
    }

    // TODO [1단계-G] Repository에 등록된 모든 세션 ID를 반환하라.
    //
    // 요구사항:
    //   - chatMemoryRepository.findConversationIds() 반환
    //
    // 검증 포인트 (1단계 시나리오 3번):
    //   - 세션 A와 세션 B로 각각 대화한 뒤 이 엔드포인트 호출
    //     → ["A-id", "B-id"] 가 나와야 한다. (세션 분리 검증)
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