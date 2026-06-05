package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

/**
 * 1주차에서 만든 Structured Output 엔드포인트.
 * 2주차에는 여기에도 OrderTools를 등록하여 Tool Calling과 Structured Output이
 * 함께 동작할 수 있는지 직접 확인한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;
    private final OrderTools orderTools;

    private ChatClient chatClient;

    // TODO [1단계-5] 이 엔드포인트에도 OrderTools를 등록하라.
    //
    // 요구사항:
    // - 1주차 구조 유지: defaultSystem + defaultAdvisors + .entity(SupportResponse.class).
    // - defaultTools(orderTools) 한 줄을 추가한다.
    //
    // 관찰 과제 (README에 기록):
    // - /api/v1/assistant(자연어) 와 /api/v1/support(JSON)의 입력 토큰 수 차이는?
    // - Structured Output과 Tool Calling이 함께 걸리면 2차 LLM 호출에서 어떤 프롬프트가 붙는가?
    //   (DEBUG 로그에서 ToolResponseMessage를 찾아본다.)

    @PostConstruct
    public void init() {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .defaultAdvisors(performanceAdvisor)
                .defaultTools(orderTools)
                .build();
    }

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req) {
        return chatClient
                .prompt()
                .user(req.message())
                .call()
                .entity(SupportResponse.class);
    }
}
