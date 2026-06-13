package com.baedal.support;

import com.baedal.support.guardrail.HandoffDetector;
import com.baedal.support.guardrail.InputGuardrailAdvisor;
import com.baedal.support.guardrail.OutputGuardrailAdvisor;
import com.baedal.support.tool.OrderTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Structured Output + Tool Calling + Chat Memory + RAG + Guardrail 통합 엔드포인트.
 * <p>
 * 5주차 변경점: Input/Output Guardrail Advisor를 체인에 추가.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
public class SupportController {

//    private final ChatClient chatClient;
//
//    public SupportController(ChatClient.Builder builder,
//                             PerformanceLoggingAdvisor performanceAdvisor,
//                             MessageChatMemoryAdvisor memoryAdvisor,
//                             QuestionAnswerAdvisor ragAdvisor,
//                             OrderTools orderTools) {
//        this.chatClient = builder
//                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
//                // TODO: ragAdvisor를 memoryAdvisor 다음, performanceAdvisor 앞에 추가하라.
//                .defaultAdvisors(memoryAdvisor, ragAdvisor, performanceAdvisor)
//                .defaultTools(orderTools)
//                .build();
//    }

    private final ChatClient chatClient;
    private final ChatClient.Builder builder;
    private final PerformanceLoggingAdvisor performanceAdvisor;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final QuestionAnswerAdvisor ragAdvisor;
    private final InputGuardrailAdvisor inputGuardrail;
    private final OutputGuardrailAdvisor outputGuardrail;
    private final HandoffDetector handoffDetector;
    private final OrderTools orderTools;

    @PostMapping
    public SupportResponse triage(@RequestBody ChatRequest req,
                                  @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        // TODO [3단계-C] Handoff 선검사를 추가하라.
        //   handoffDetector.detect(req.message())의 handoff==true면 Structured Output 스키마에 맞춰
        //   SupportResponse를 수동 조립하여 반환한다.
        //     - answer: decision.message()
        //     - category: Category.ETC
        //     - urgency:  Urgency.HIGH
        //     - action:   "상담원 연결 진행"
        //     - nextSteps: List.of() 또는 ["상담원 응대 대기"]

        // 3단계 구현
        HandoffDetector.HandoffDecision decision = handoffDetector.detect(req.message());
        if (decision.handoff()) {
            return new SupportResponse(
                    decision.message(),
                    SupportResponse.Category.ETC,
                    SupportResponse.Urgency.HIGH,
                    "상담원 연결 진행",
                    SupportResponse.AffectedParty.PLATFORM,
                    true,
                    List.of("상담원 응대 대기")
            );
        }

//        return builder
//                .defaultSystem(BaedalPrompt.SYSTEM_PROMPT)
//                .defaultAdvisors(inputGuardrail, memoryAdvisor, ragAdvisor, outputGuardrail, performanceAdvisor)
//                .defaultTools(orderTools)
//                .build()
//                .prompt()
//                .user(req.message())
//                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
//                .call()
//                .entity(SupportResponse.class);

        return chatClient
                .prompt()
                .user(req.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .entity(SupportResponse.class);
    }
}
