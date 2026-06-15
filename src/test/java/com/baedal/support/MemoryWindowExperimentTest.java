package com.baedal.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3주차 2단계 — Memory 슬라이딩 윈도우의 결정적 시뮬레이션.
 *
 * <p>LLM(Ollama)을 거치지 않고 {@link MessageWindowChatMemory} + {@link InMemoryChatMemoryRepository}만
 * 직접 조립해서, {@code maxMessages = 2 / 20 / Integer.MAX_VALUE} 세 값이
 * 같은 10턴 시퀀스에 어떻게 반응하는지 *값으로* 증명한다.
 *
 * <p>"왜 라이브 호출이 아니라 시뮬레이션인가":
 * <ul>
 *     <li>윈도우 메커니즘 자체는 결정적 — LLM 비결정성과 분리해서 검증해야 한다.</li>
 *     <li>"지시 대명사 해결 실패"는 결국 "필요한 orderId가 윈도우에 안 남았다"는 사건이다.
 *         이것은 메시지를 직접 주입해서 검증할 수 있다.</li>
 *     <li>토큰/응답 시간 측정은 별도 라이브 실행 (scripts/week3-stage2.sh) 에서 수집한다.</li>
 * </ul>
 */
class MemoryWindowExperimentTest {

    private static final String SESSION_ID = "stage2-sim";

    /**
     * 숙제 가이드의 10턴 시퀀스. 각 턴은 USER → ASSISTANT 한 쌍.
     * ASSISTANT 응답은 LLM이 실제로 줄 법한 *간략한* 자리표시자이며,
     * 검증의 핵심은 응답 텍스트가 아니라 "윈도우에 어떤 orderId가 남는가" 이다.
     */
    private static final List<Turn> TEN_TURN_SCENARIO = List.of(
            new Turn("2024-1234 배달 상황 알려주세요",
                    "주문 2024-1234는 현재 배달 중입니다. 라이더는 역삼역 사거리 부근입니다."),
            new Turn("그거 몇 분 남았어요?",
                    "주문 2024-1234는 약 15분 후 도착 예정입니다."),
            new Turn("2024-1235 주문도 있는데 메뉴 뭐였죠?",
                    "주문 2024-1235는 빅맥 세트 2개 (19,000원) 입니다."),
            new Turn("아 그 버거 세트",
                    "네 빅맥 세트 2개로 확인됩니다."),
            new Turn("2024-1234 취소 가능해요?",
                    "주문 2024-1234는 이미 배달 중이라 자동 취소가 불가합니다."),
            new Turn("그럼 1235는 취소되죠?",
                    "주문 2024-1235는 접수 직후라 취소 가능합니다."),
            new Turn("그거 취소해주세요",
                    "주문 2024-1235를 취소했습니다."),
            new Turn("아까 1234는 언제 도착해요?",
                    "주문 2024-1234는 약 8분 후 도착 예정입니다."),
            new Turn("그 주문 라이더 위치 다시 확인",
                    "주문 2024-1234 라이더는 강남역 부근입니다."),
            new Turn("요약해 주세요 지금까지 제가 뭘 물어봤는지",
                    "고객님은 2024-1234 배달 상태와 2024-1235 주문 취소를 문의하셨습니다.")
    );

    private record Turn(String userText, String assistantText) {}

    /**
     * MAX_MESSAGES = N 으로 10턴 시퀀스를 모두 주입한 뒤의 윈도우 상태를 반환한다.
     */
    private List<Message> runTenTurns(int maxMessages) {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxMessages)
                .build();
        for (Turn t : TEN_TURN_SCENARIO) {
            memory.add(SESSION_ID, new UserMessage(t.userText()));
            memory.add(SESSION_ID, new AssistantMessage(t.assistantText()));
        }
        return memory.get(SESSION_ID);
    }

    private static int charCount(List<Message> messages) {
        return messages.stream().mapToInt(m -> m.getText().length()).sum();
    }

    private static boolean containsOrderId(List<Message> messages, String orderId) {
        return messages.stream().anyMatch(m -> m.getText().contains(orderId));
    }

    // ─────────────────────────────────────────────────────────────────
    // 실험 A: MAX_MESSAGES = 20
    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("실험 A: MAX_MESSAGES = 20 (기준)")
    class ExperimentA {

        @Test
        @DisplayName("10턴(=20 메시지) 모두 윈도우에 남는다")
        void all_twenty_messages_fit() {
            List<Message> window = runTenTurns(20);
            assertThat(window).hasSize(20);
        }

        @Test
        @DisplayName("turn 1의 1234와 turn 3의 1235가 turn 10 시점에도 모두 살아있다")
        void both_orderIds_survive_until_last_turn() {
            List<Message> window = runTenTurns(20);
            assertThat(containsOrderId(window, "2024-1234"))
                    .as("배달 문의의 원래 주문번호가 윈도우에 보존되어야 한다")
                    .isTrue();
            assertThat(containsOrderId(window, "2024-1235"))
                    .as("취소 대상 주문번호가 윈도우에 보존되어야 한다")
                    .isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 실험 B: MAX_MESSAGES = 2 (극단적으로 작음)
    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("실험 B: MAX_MESSAGES = 2 (지시 대명사 해결 실패 관찰)")
    class ExperimentB {

        @Test
        @DisplayName("10턴 후 윈도우에는 마지막 USER/ASSISTANT 한 쌍만 남는다")
        void only_last_pair_survives() {
            List<Message> window = runTenTurns(2);
            assertThat(window).hasSize(2);
            assertThat(window.get(0).getText()).contains("요약해 주세요");
            assertThat(window.get(1).getText()).contains("2024-1234")
                    .as("마지막 ASSISTANT 응답에 1234가 우연히 포함된다 — " +
                        "만약 마지막 응답이 짧았다면 이마저도 없다");
        }

        /**
         * 핵심 실패 시나리오: turn 3에서 1235를 언급했지만,
         * turn 5의 "1234 취소 가능해요?" 시점에 윈도우는 [turn 4 USER, turn 4 ASSISTANT] 뿐.
         * turn 4는 "아 그 버거 세트" / "네 빅맥 세트 2개로 확인됩니다." — 둘 다 orderId 없음.
         * LLM은 "1234가 무엇인지" 알 수 없다.
         */
        @Test
        @DisplayName("turn 5 시점의 윈도우는 turn 4 한 쌍뿐이고, 둘 다 orderId가 없다")
        void window_at_turn_5_has_no_orderId_context() {
            ChatMemory memory = MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(2)
                    .build();
            for (int i = 0; i < 4; i++) {
                Turn t = TEN_TURN_SCENARIO.get(i);
                memory.add(SESSION_ID, new UserMessage(t.userText()));
                memory.add(SESSION_ID, new AssistantMessage(t.assistantText()));
            }
            List<Message> windowBeforeTurn5 = memory.get(SESSION_ID);

            assertThat(windowBeforeTurn5).hasSize(2);
            assertThat(containsOrderId(windowBeforeTurn5, "2024-1234")).isFalse();
            assertThat(containsOrderId(windowBeforeTurn5, "2024-1235")).isFalse();
        }

        /**
         * 더 극명한 실패: turn 1에서 1234를 처음 언급한 뒤, 3턴이 지나면
         * 1234는 윈도우에서 완전히 사라진다. 이후 "그 주문" 같은 지시 대명사로
         * 1234를 다시 가리키려 해도 LLM에게 단서가 없다.
         */
        @Test
        @DisplayName("turn 1의 1234는 turn 4 시점에 윈도우에서 완전히 사라진다 — 지시 대명사 해결 불가")
        void earliest_orderId_disappears_after_3_turns() {
            ChatMemory memory = MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(2)
                    .build();
            // turn 1만 1234를 언급. turn 2~3은 1234 / 1235 모두 언급 없는 다른 대화로 채운다.
            memory.add(SESSION_ID, new UserMessage("2024-1234 배달 상황 알려주세요"));
            memory.add(SESSION_ID, new AssistantMessage("주문 2024-1234 배달 중입니다."));
            memory.add(SESSION_ID, new UserMessage("고마워요"));
            memory.add(SESSION_ID, new AssistantMessage("도움 드려서 기쁩니다."));
            memory.add(SESSION_ID, new UserMessage("배달비는 얼마였죠?"));
            memory.add(SESSION_ID, new AssistantMessage("배달비는 3,000원입니다."));

            // 이 시점에 사용자가 "그 주문 취소해주세요" 라고 하면?
            List<Message> windowAtThisPoint = memory.get(SESSION_ID);
            assertThat(windowAtThisPoint).hasSize(2);
            assertThat(containsOrderId(windowAtThisPoint, "2024-1234"))
                    .as("MAX_MESSAGES=2 윈도우에 1234가 남아 있는가")
                    .isFalse();
            // → LLM은 "그 주문"이 무엇인지 추론할 수 없다.
            //   가이드의 실패 관찰: "지시 대명사가 엉뚱하게 해석된 턴" 이 바로 이 케이스.
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 실험 C: MAX_MESSAGES = Integer.MAX_VALUE (사실상 무제한)
    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("실험 C: MAX_MESSAGES = Integer.MAX_VALUE (입력 토큰 선형 증가)")
    class ExperimentC {

        @Test
        @DisplayName("10턴 후에도 20개 메시지가 모두 누적된다")
        void all_messages_accumulate() {
            List<Message> window = runTenTurns(Integer.MAX_VALUE);
            assertThat(window).hasSize(20);
        }

        @Test
        @DisplayName("문자 수가 턴이 진행될수록 단조 증가한다 — 토큰 선형 증가의 직접 원인")
        void character_count_grows_monotonically_per_turn() {
            ChatMemory memory = MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(Integer.MAX_VALUE)
                    .build();
            int[] perTurnChars = new int[TEN_TURN_SCENARIO.size()];
            for (int i = 0; i < TEN_TURN_SCENARIO.size(); i++) {
                Turn t = TEN_TURN_SCENARIO.get(i);
                memory.add(SESSION_ID, new UserMessage(t.userText()));
                memory.add(SESSION_ID, new AssistantMessage(t.assistantText()));
                perTurnChars[i] = charCount(memory.get(SESSION_ID));
            }
            for (int i = 1; i < perTurnChars.length; i++) {
                assertThat(perTurnChars[i])
                        .as("turn %d 시점의 윈도우 문자 수는 turn %d보다 커야 한다", i + 1, i)
                        .isGreaterThan(perTurnChars[i - 1]);
            }
            // 1턴 vs 10턴 누적 문자 수 비율을 출력해 두면, 라이브 실행 시 측정한 입력 토큰 비율과 비교 가능.
            System.out.printf("[ExperimentC] 1턴=%d chars, 10턴=%d chars, ratio=%.2fx%n",
                    perTurnChars[0], perTurnChars[9],
                    (double) perTurnChars[9] / perTurnChars[0]);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 윈도우 크기별 누적 문자 수 — 정량 비교
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("정량 비교: MAX_MESSAGES = 2 / 20 / MAX_VALUE 의 최종 윈도우 문자 수")
    void quantitative_comparison_table() {
        int charsB = charCount(runTenTurns(2));
        int charsA = charCount(runTenTurns(20));
        int charsC = charCount(runTenTurns(Integer.MAX_VALUE));

        // 결정적 단조 관계: B < A <= C (20과 MAX_VALUE는 메시지 수가 20개로 같아 동일)
        assertThat(charsB).isLessThan(charsA);
        assertThat(charsA).isEqualTo(charsC);

        System.out.println("─── MAX_MESSAGES별 최종 윈도우 문자 수 ───");
        System.out.printf("B (max=2)           : %5d chars (마지막 한 쌍만)%n", charsB);
        System.out.printf("A (max=20)          : %5d chars (10턴 전체)%n", charsA);
        System.out.printf("C (max=MAX_VALUE)   : %5d chars (10턴 전체와 동일 — 차이는 11턴+에서 발생)%n", charsC);
    }
}