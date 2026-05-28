package com.baedal.support.controller;

import com.baedal.support.advisor.PerformanceLoggingAdvisor;
import com.baedal.support.dto.SupportResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/prompt-lab")
public class PromptLabController {

    // ChatModel 직접 주입 — 매 요청마다 fresh builder 를 만들어 누적 빌더 bug 회피
    // (ChatClient.Builder 를 주입받아 재사용하면 매 호출마다 defaultSystem·defaultAdvisors 가 누적될 위험)
    private final ChatModel chatModel;
    private final PerformanceLoggingAdvisor performanceAdvisor;

    /**
     * 같은 입력을 N번 호출하여 분류·자유 텍스트 품질을 다축으로 측정한다.
     *
     * 분류 단위 (enum 영역):
     *   - categoryConsistency, intentConsistency, urgencyConsistency, recommendedRoutingConsistency
     *
     * 품질 단위 (자유 텍스트 / 입력 정합성):
     *   - customerMessageEchoRate     : customerMessage가 입력을 그대로 반복하는 비율 (Jaccard ≥ 0.5)
     *   - prohibitionViolationRate    : 전화번호·확정 약속 어구·경쟁사 이름 노출 비율 (키워드 검사, 오탐 가능)
     *   - koreanResponseRate          : summary·customerMessage·nextAction 모두 한국어인 비율
     *   - missingInfoAccuracy         : 입력에 주문번호 있을 때 missingInfo가 비어 있는 비율
     */
    @PostMapping
    public PromptLabResult experiment(@Valid @RequestBody PromptLabRequest req) {
        ChatClient client = ChatClient.builder(chatModel)   // 매 요청마다 fresh
                .defaultSystem(req.systemPrompt())
                .defaultAdvisors(performanceAdvisor)
                .build();
        List<SupportResponse> results = new ArrayList<>(req.repeat());
        for (int i = 0; i < req.repeat(); i++) {
            results.add(client.prompt()
                    .user(req.message())
                    .call()
                    .entity(SupportResponse.class));
        }
        return PromptLabResult.from(results, req.message());
    }

    public record PromptLabRequest(
            @NotBlank String systemPrompt,
            @NotBlank String message,
            @Min(1) int repeat
    ) {}

    public record PromptLabResult(
            int totalRuns,
            // 분류 통계
            Map<String, Long> categoryCounts,
            Map<String, Long> intentCounts,
            Map<String, Long> urgencyCounts,
            Map<String, Long> recommendedRoutingCounts,
            double categoryConsistency,
            double intentConsistency,
            double urgencyConsistency,
            double recommendedRoutingConsistency,
            // 품질 메트릭
            double customerMessageEchoRate,
            double prohibitionViolationRate,
            double koreanResponseRate,
            double missingInfoAccuracy,
            // 사후 분석·인용용 raw
            List<SupportResponse> responses
    ) {
        private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{2,4}-\\d{3,4}-\\d{4}");
        private static final Pattern ORDER_NUMBER_PATTERN = Pattern.compile("\\d{4}-\\d{3,4}|주문번호\\s*\\d+");
        private static final List<String> COMMIT_PHRASES = List.of(
                "환불해드리겠습니다", "환불해드릴게요",
                "보상해드리겠습니다", "보상해드릴게요",
                "쿠폰을 드리겠습니다", "쿠폰 발급해드리겠습니다",
                "재배송해드리겠습니다"
        );
        private static final List<String> COMPETITOR_NAMES = List.of("쿠팡이츠", "요기요", "배달의민족");

        public static PromptLabResult from(List<SupportResponse> results, String userMessage) {
            int total = results.size();

            Map<String, Long> cat = countBy(results, r -> r.category().name());
            Map<String, Long> intent = countBy(results, r -> r.intent() == null ? "NULL" : r.intent().name());
            Map<String, Long> urg = countBy(results, r -> r.urgency().name());
            Map<String, Long> routing = countBy(results, r -> r.recommendedRouting() == null ? "NULL" : r.recommendedRouting().name());

            double echoRate = rateOf(results, r -> isEcho(userMessage, r.customerMessage()));
            double prohRate = rateOf(results, PromptLabResult::hasProhibitionViolation);
            double koRate = rateOf(results, PromptLabResult::isKoreanResponse);
            double missAcc = rateOf(results, r -> isMissingInfoAccurate(userMessage, r));

            return new PromptLabResult(
                    total,
                    cat, intent, urg, routing,
                    consistency(cat, total),
                    consistency(intent, total),
                    consistency(urg, total),
                    consistency(routing, total),
                    echoRate, prohRate, koRate, missAcc,
                    results
            );
        }

        private static Map<String, Long> countBy(List<SupportResponse> results, Function<SupportResponse, String> keyFn) {
            return results.stream().collect(Collectors.groupingBy(keyFn, Collectors.counting()));
        }

        private static double consistency(Map<String, Long> counts, int total) {
            if (total == 0) return 0;
            long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
            return (double) max / total;
        }

        private static double rateOf(List<SupportResponse> results, java.util.function.Predicate<SupportResponse> pred) {
            if (results.isEmpty()) return 0;
            return (double) results.stream().filter(pred).count() / results.size();
        }

        // === 품질 메트릭 ===

        /**
         * customerMessage 가 입력 메시지를 그대로 반복하거나 거의 같은 표현인지.
         * exact match 또는 공백 토큰 Jaccard 유사도 ≥ 0.5 이면 echo로 판정.
         * 한국어 paraphrase 미세한 경우는 false negative 가능 (보수적 측정).
         */
        private static boolean isEcho(String userMessage, String customerMessage) {
            if (userMessage == null || customerMessage == null) return false;
            String u = userMessage.trim();
            String c = customerMessage.trim();
            if (u.isEmpty() || c.isEmpty()) return false;
            if (u.equals(c)) return true;
            Set<String> uTokens = Arrays.stream(u.split("\\s+")).collect(Collectors.toSet());
            Set<String> cTokens = Arrays.stream(c.split("\\s+")).collect(Collectors.toSet());
            Set<String> intersect = new HashSet<>(uTokens);
            intersect.retainAll(cTokens);
            Set<String> union = new HashSet<>(uTokens);
            union.addAll(cTokens);
            return union.isEmpty() ? false : (double) intersect.size() / union.size() >= 0.5;
        }

        /**
         * 응답에 전화번호 패턴, 확정 약속 어구, 경쟁사 이름이 포함되었는지.
         * 키워드 검사 — 오탐 가능 (예: "전화번호는 제공하지 않습니다" 같은 거절 응답도 잡힐 수 있음).
         * 진짜 위반 여부는 raw responses 인용으로 사람이 확인해야 한다.
         */
        private static boolean hasProhibitionViolation(SupportResponse r) {
            String all = String.join(" ",
                    nullSafe(r.summary()),
                    nullSafe(r.customerMessage()),
                    nullSafe(r.nextAction()));
            if (PHONE_PATTERN.matcher(all).find()) return true;
            for (String phrase : COMMIT_PHRASES) {
                if (all.contains(phrase)) return true;
            }
            for (String name : COMPETITOR_NAMES) {
                if (all.contains(name)) return true;
            }
            return false;
        }

        /** summary·customerMessage·nextAction 모두 한국어인지 (각 한글 비율 ≥ 30%). */
        private static boolean isKoreanResponse(SupportResponse r) {
            return isKorean(r.summary()) && isKorean(r.customerMessage()) && isKorean(r.nextAction());
        }

        private static boolean isKorean(String text) {
            if (text == null || text.isBlank()) return false;
            long korean = text.chars().filter(c -> c >= 0xAC00 && c <= 0xD7A3).count();
            long nonSpace = text.chars().filter(c -> !Character.isWhitespace(c)).count();
            return nonSpace > 0 && (double) korean / nonSpace >= 0.3;
        }

        /**
         * 입력에 주문번호 패턴이 있으면 missingInfo 에 orderNumber 가 없어야 정확.
         * 입력에 없으면 시나리오별 필요 정보가 다양하므로 단순 검사를 건너뛰고 true 처리.
         */
        private static boolean isMissingInfoAccurate(String userMessage, SupportResponse r) {
            if (userMessage == null || r.missingInfo() == null) return true;
            boolean hasOrderInInput = ORDER_NUMBER_PATTERN.matcher(userMessage).find();
            if (!hasOrderInInput) return true;
            boolean missingHasOrder = r.missingInfo().stream()
                    .anyMatch(s -> s != null && s.toLowerCase().contains("order"));
            return !missingHasOrder;
        }

        private static String nullSafe(String s) {
            return s == null ? "" : s;
        }
    }
}
