package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling이 적용된 자연어 응답 엔드포인트.
 * <p>
 * {@code /api/v1/support}가 Structured Output(JSON)을 반환하는 데 반해,
 * 이 엔드포인트는 <b>Tool 호출의 흐름을 평문으로 관찰</b>하기 위한 용도다.
 * DEBUG 로그와 함께 보면 Tool이 언제 어떻게 호출되는지 직관적으로 이해할 수 있다.
 */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    public AssistantController(ChatClient.Builder builder,
                               PerformanceLoggingAdvisor performanceAdvisor,
                               OrderTools orderTools) {
        this.chatClient = builder
                .defaultSystem("당신은 배달 상담 AI입니다. 주문 조회, 배달 현황, 취소 요청을 처리합니다. 주문 관련 요청은 반드시 도구를 먼저 호출하여 처리하세요. 도구 호출 전에 다른 텍스트를 출력하지 마세요.")
                .defaultAdvisors(performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public String ask(@RequestBody ChatRequest req) {
        return chatClient.prompt()
                .user(req.message())
                .call()
                .content();
    }
}