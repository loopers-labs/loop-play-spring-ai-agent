package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling + Chat Memory가 적용된 자연어 응답 엔드포인트.
 *
 * <p>3주차 변경점 (숙제에서 직접 구현):
 * <ul>
 *     <li>{@link MessageChatMemoryAdvisor}를 Advisor 체인에 추가 — 이전 대화 이력을 자동 주입
 *         (Advisor 등록은 {@link AssistantChatClientConfig}에서 수행)</li>
 *     <li>{@code X-Session-Id} HTTP 헤더로 고객 세션을 식별하고
 *         {@link ChatMemory#CONVERSATION_ID} 파라미터에 전달</li>
 *     <li>헤더가 없으면 {@code "default"} 세션으로 폴백 (개발용)</li>
 * </ul>
 *
 * <p>구현 후 관찰 포인트: 같은 세션 ID로 연속 호출 시 "그거", "방금 주문한 거" 같은
 * 지시 대명사가 Tool 호출 파라미터(orderId)로 정확히 치환되는 과정을 DEBUG 로그에서 확인할 수 있다.
 *
 * <p>구조 메모: {@link ChatClient.Builder}가 아니라 조립이 끝난 {@link ChatClient}를 주입받는다.
 * Builder는 싱글톤이라 요청마다 defaultTools/defaultAdvisors를 호출하면 누적되어
 * "Multiple tools with the same name" 오류가 발생하기 때문이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    /* .defaultTools(...) vs .tools(...) — 생성자에서 build()를 한 번만 호출해
           ChatClient를 재사용하면 defaultTools()가 이 컨트롤러의 모든 호출에 적용된다.
           반면 .tools()는 prompt() 체인에서 개별 호출에만 적용할 때 사용한다.*/
    public AssistantController(ChatClient.Builder builder,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.ASSISTANT_SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor, new SimpleLoggerAdvisor())
                .defaultTools(orderTools)
                .build();
    }

    // TODO [1단계-H] X-Session-Id 헤더를 받아 Memory에 연결하라.
    //
    // 요구사항:
    //   1) 메서드 파라미터에 다음을 추가:
    //         @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId
    //   2) log.info("[Assistant] sessionId={}, message={}", sessionId, req.message()); 로 관찰 포인트 확보
    //   3) 이 호출에 한해 Memory가 사용할 conversationId를 지정:
    //         .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
    //
    // 왜 "이 호출에 한해" 지정하는가:
    //   - 같은 ChatClient Bean을 여러 세션이 공유한다.
    //   - 요청마다 sessionId가 다르므로 고정값으로 둘 수 없다.
    //   - .advisors(...) 는 prompt() 체인에만 적용되어 스레드 간섭이 없다.
    //
    // 설계 결정 질문 (README):
    //   - defaultValue = "default" 정책의 위험은 무엇인가?
    //     (힌트: 헤더를 안 보낸 여러 고객의 대화가 섞여 버린다 = 심각한 개인정보 사고)
    //   - 프로덕션에서 세션 식별의 실무 대안(쿠키 / JWT 클레임 / URL 경로)은 각각 어떤 장단점이 있는가?
    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        // TODO: @RequestHeader로 sessionId를 받고, .advisors(...) 로 conversationId를 주입하라.
        // TODO: .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
        return chatClient
                .prompt()
                .user(req.message())
                .call()
                .content();
    }
}
