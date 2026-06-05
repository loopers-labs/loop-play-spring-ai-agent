package com.baedal.support;

import com.baedal.support.tool.OrderTools;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;
    private final OrderTools orderTools;

    private ChatClient chatClient;

    // TODO [1단계-4] 이 엔드포인트에 OrderTools를 등록하라.
    //
    // 요구사항:
    // - builder.defaultSystem(BaedalPrompt.SYSTEM_PROMPT) 로 시스템 프롬프트를 건다.
    // - builder.defaultAdvisors(performanceAdvisor) 로 관찰 로깅을 건다.
    // - builder.defaultTools(orderTools) 로 Tool을 등록한다.  <-- 2주차 핵심!
    // - .build().prompt().user(req.message()).call().content() 로 자연어 응답을 돌려준다.
    //
    // 힌트: .defaultTools(...) vs .tools(...) — 전자는 이 컨트롤러의 모든 호출에 적용,
    //      후자는 개별 호출에만 적용된다. 여기서는 defaultTools()가 맞다.

    @PostConstruct
    public void init() {
        this.chatClient = builder
                .defaultSystem(BaedalPrompt.ASSISTANT_PROMPT)
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
