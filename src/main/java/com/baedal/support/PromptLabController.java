package com.baedal.support;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/prompt-lab")
public class PromptLabController {

    private final ChatClient.Builder builder;

    @PostMapping
    public PromptLabResult experiment(@RequestBody PromptLabRequest request) {
        ChatClient client = builder
                .defaultSystem(request.systemPrompt())
                .build();

        List<SupportResponse> results = new ArrayList<>();
        for (int i = 0; i < request.repeat(); i++) {
            var prompt = client.prompt().user(request.message());
            if (request.temperature() != null) {
                prompt = prompt.options(OllamaOptions.builder().temperature(request.temperature()).build());
            }
            results.add(prompt.call().entity(SupportResponse.class));
        }

        return PromptLabResult.from(results);
    }

    public record PromptLabRequest(
            String systemPrompt,
            String message,
            int repeat,
            Double temperature
    ) {}

    public record PromptLabResult(
            int totalRuns,
            Map<String, Long> categoryCounts,
            Map<String, Long> urgencyCounts,
            double categoryConsistency,
            double nextActionComplianceRate,
            double prohibitionViolationRate,
            double neededInfoCompletenessRate,
            List<SupportResponse> individualResults
    ) {
        // [응답 포맷] nextAction 허용 값 3가지
        private static final Set<String> ALLOWED_NEXT_ACTIONS = Set.of(
                "주문 상태 조회 요청",
                "추가 정보 확인",
                "상담사 연결"
        );

        // [금지] 규칙 위반 키워드 (맥락 무관하게 항상 금지인 표현만 포함)
        // - 경쟁사 언급 금지: 쿠팡이츠, 요기요, 배달통
        // - 보상 약속 금지: 환불해드리겠습니다, 쿠폰 드리겠습니다, 발급해드리겠습니다
        // - 개인정보 노출 금지: 연락처는, 전화번호는, 주소는
        // - 시점·결과 단정 금지: 오늘 안에 처리됩니다, 3일 이내에 환불됩니다
        // 주의: "감사합니다"는 맥락 의존적(불만 상황에서만 금지)이므로 키워드 탐지 대상에서 제외.
        //       맥락 판단이 필요한 항목은 LLM-as-a-Judge 방식으로 측정해야 한다.
        private static final List<String> VIOLATION_KEYWORDS = List.of(
                "쿠팡이츠", "요기요", "배달통",
                "환불해드리겠습니다", "쿠폰 드리겠습니다", "발급해드리겠습니다",
                "연락처는", "전화번호는", "주소는",
                "오늘 안에 처리됩니다", "3일 이내에 환불됩니다"
        );

        public static PromptLabResult from(List<SupportResponse> results) {
            if (results.isEmpty()) {
                return new PromptLabResult(0, Map.of(), Map.of(), 0, 0, 0, 0, List.of());
            }

            Map<String, Long> catCounts = results.stream()
                    .collect(Collectors.groupingBy(r -> r.category().name(), Collectors.counting()));

            Map<String, Long> urgCounts = results.stream()
                    .collect(Collectors.groupingBy(r -> r.urgency().name(), Collectors.counting()));

            long maxCat = catCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);

            long compliantCount = results.stream()
                    .filter(r -> ALLOWED_NEXT_ACTIONS.contains(r.nextAction()))
                    .count();

            long violationCount = results.stream()
                    .filter(r -> VIOLATION_KEYWORDS.stream().anyMatch(kw -> r.summary().contains(kw)))
                    .count();

            // nextAction이 "주문 상태 조회 요청"일 때 neededInfo에 "주문번호"가 포함된 비율
            // 주문번호 없이는 상태 조회가 불가능하므로, 이 경우 neededInfo가 비어 있으면 불완전한 응답
            List<SupportResponse> orderQueryResults = results.stream()
                    .filter(r -> "주문 상태 조회 요청".equals(r.nextAction()))
                    .toList();

            long completeNeededInfoCount = orderQueryResults.stream()
                    .filter(r -> r.neededInfo() != null && r.neededInfo().contains("주문번호"))
                    .count();

            double neededInfoCompletenessRate = orderQueryResults.isEmpty()
                    ? 1.0
                    : (double) completeNeededInfoCount / orderQueryResults.size();

            return new PromptLabResult(
                    results.size(),
                    catCounts,
                    urgCounts,
                    (double) maxCat / results.size(),
                    (double) compliantCount / results.size(),
                    (double) violationCount / results.size(),
                    neededInfoCompletenessRate,
                    results
            );
        }
    }
}
