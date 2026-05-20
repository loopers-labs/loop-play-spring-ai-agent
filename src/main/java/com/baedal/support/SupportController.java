package com.baedal.support;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
public class SupportController {

    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;

    // TODO [1단계]: BaedalPrompt.SYSTEM_PROMPT를 적용하고 Structured Output을 반환하라.
    //
    // 구현 힌트:
    // 1. builder.defaultSystem(...)으로 System Prompt를 설정한다.
    // 2. .build().prompt().user(req.message()).call()으로 LLM을 호출한다.
    // 3. .entity(SupportResponse.class)로 JSON -> DTO 변환을 받는다.
    //
    // 4단계에서 PerformanceLoggingAdvisor를 구현한 후,
    // .defaultAdvisors(...)로 등록하여 토큰 수와 응답 시간을 로깅하라.
    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req) {
        return builder
                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
                .build()
                .prompt()
                .advisors(performanceAdvisor)
                .user(req.message())
                .call()
                .entity(SupportResponse.class);
    }
}
