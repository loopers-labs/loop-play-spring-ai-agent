package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient chatClient;

    // 2주차: Structured Output(JSON) 엔드포인트에도 OrderTools를 등록.
    // 3주차: memoryAdvisor를 (performance보다 먼저) 추가해 같은 세션 맥락을 공유.
    // 4주차: ragAdvisor(order 20)를 memory 뒤·performance 앞에 추가해 정책/FAQ를 검색·주입.
    //        ChatClient는 생성자에서 1회만 build (Builder 누적버그 회피), conversationId는 요청별 주입.
    public SupportController(ChatClient.Builder builder,
                             PerformanceLoggingAdvisor performanceAdvisor,
                             MessageChatMemoryAdvisor memoryAdvisor,
                             QuestionAnswerAdvisor ragAdvisor,
                             OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@Valid @RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {
        try {
            return chatClient
                    .prompt()
                    .user(req.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .entity(SupportResponse.class);
        } catch (Exception e) {
            log.error("LLM triage call failed", e);
            throw new SupportServiceException("고객 문의 처리 중 오류가 발생했습니다.", e);
        }
    }
}
