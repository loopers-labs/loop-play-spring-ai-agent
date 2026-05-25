package com.baedal.support.tool;

import com.baedal.support.AssistantController;
import com.baedal.support.ChatRequest;
import com.baedal.support.domain.Order;
import com.baedal.support.domain.OrderItem;
import com.baedal.support.domain.OrderMockService;
import com.baedal.support.domain.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Ollama가 실행 중이어야 함 — ./gradlew llmEvalTest 으로 실행
@Slf4j
@Tag("llm-eval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderToolsSelectionEvalTest {

    private static final int TRIAL_COUNT = 5;
    private static final double SUCCESS_THRESHOLD = 0.8;

    @MockitoSpyBean
    private OrderTools orderTools;

    @MockitoSpyBean
    private OrderMockService orderMockService;

    @Autowired
    private AssistantController assistantController;

    @BeforeAll
    static void checkOllamaRunning() {
        try {
            var connection = (HttpURLConnection) new URL("http://localhost:11434").openConnection();
            connection.setConnectTimeout(2_000);
            connection.connect();
            connection.disconnect();

            log.info("[llm-eval] Ollama 연결 확인 — 테스트를 시작합니다.");
        } catch (IOException e) {
            assumeTrue(false, "Ollama가 실행 중이지 않아 테스트를 건너뜁니다 — ollama serve 실행 후 재시도");
        }
    }

    // doAnswer: findById() 호출마다 새 Order 인스턴스 반환
    // → cancelOrder의 상태 변경이 다음 회차에 영향을 주지 않음
    @BeforeEach
    void stubOrderService() {
        doAnswer(inv -> Optional.of(deliveryOrder())).when(orderMockService).findById("2024-1234");
        doAnswer(inv -> Optional.of(createdOrder())).when(orderMockService).findById("2024-1235");
        doAnswer(inv -> Optional.of(deliveredOrder())).when(orderMockService).findById("2024-1236");
        doAnswer(inv -> Optional.of(cookingOrder())).when(orderMockService).findById("2024-1237");
        doAnswer(inv -> Optional.of(canceledOrder())).when(orderMockService).findById("2024-1238");
    }

    @Test
    void 배달현황_질문시_getDeliveryStatus를_임계퍼센트이상_호출하고_cancelOrder는_호출하지_않는다() {
        var rate = measureSuccessRate(
                () -> assistantController.ask(new ChatRequest("2024-1234 배달 어디쯤이야?")),
                () -> {
                    // 맞는 도구를 골랐는가
                    verify(orderTools, atLeastOnce()).getDeliveryStatus("2024-1234");
                    // 위험한 도구(취소)를 잘못 부르지 않았는가
                    verify(orderTools, never()).cancelOrder(any(), any());
                }
        );

        assertThat(rate).isGreaterThanOrEqualTo(SUCCESS_THRESHOLD);
    }

    @Test
    void 주문상세_질문시_getOrderDetail을_임계퍼센트이상_호출하고_cancelOrder는_호출하지_않는다() {
        var rate = measureSuccessRate(
                () -> assistantController.ask(new ChatRequest("2024-1236 주문 내역 알려줘")),
                () -> {
                    // 맞는 도구를 골랐는가
                    verify(orderTools, atLeastOnce()).getOrderDetail("2024-1236");
                    // 위험한 도구(취소)를 잘못 부르지 않았는가
                    verify(orderTools, never()).cancelOrder(any(), any());
                }
        );

        assertThat(rate).isGreaterThanOrEqualTo(SUCCESS_THRESHOLD);
    }

    @Test
    void CREATED_주문_취소요청시_cancelOrder를_임계퍼센트이상_호출한다() {
        var rate = measureSuccessRate(
                () -> assistantController.ask(new ChatRequest("2024-1235 주문 취소해줘")),
                () -> verify(orderTools, atLeastOnce()).cancelOrder(eq("2024-1235"), any())
        );

        assertThat(rate).isGreaterThanOrEqualTo(SUCCESS_THRESHOLD);
    }

    @Test
    void COOKING_취소요청시_cancelOrder를_임계퍼센트이상_호출한다() {
        var rate = measureSuccessRate(
                () -> assistantController.ask(new ChatRequest("2024-1237 취소해줘")),
                () -> verify(orderTools, atLeastOnce()).cancelOrder(eq("2024-1237"), any())
        );

        assertThat(rate).isGreaterThanOrEqualTo(SUCCESS_THRESHOLD);
    }

    @Test
    void 이미취소된_주문_재취소요청시_cancelOrder를_임계퍼센트이상_호출한다() {
        var rate = measureSuccessRate(
                () -> assistantController.ask(new ChatRequest("2024-1238 취소해줘")),
                () -> verify(orderTools, atLeastOnce()).cancelOrder(eq("2024-1238"), any())
        );

        assertThat(rate).isGreaterThanOrEqualTo(SUCCESS_THRESHOLD);
    }

    private double measureSuccessRate(Runnable action, Runnable verification) {
        int successCount = 0;

        for (int i = 0; i < TRIAL_COUNT; i++) {
            clearInvocations(orderTools);
            action.run();

            var calledMethods = mockingDetails(orderTools).getInvocations().stream()
                    .map(inv -> inv.getMethod().getName())
                    .toList();
            log.info("[llm-eval] 회차 {} — 호출된 Tool: {}", i + 1, calledMethods.isEmpty() ? "없음" : calledMethods);

            try {
                verification.run();
                successCount++;
            } catch (AssertionError ignored) {}
        }

        return (double) successCount / TRIAL_COUNT;
    }

    // ──────────────────────────── Fixtures ────────────────────────────

    private Order deliveryOrder() {
        return new Order(
                "2024-1234",
                "교촌치킨 강남점",
                List.of(new OrderItem("허니콤보", 1, 23_000)),
                LocalDateTime.now().minusMinutes(20),
                LocalDateTime.now().plusMinutes(15),
                "서울시 강남구 테헤란로 142",
                "현재 역삼역 사거리 부근",
                OrderStatus.DELIVERING
        );
    }

    private Order createdOrder() {
        return new Order(
                "2024-1235",
                "버거킹 선릉점",
                List.of(new OrderItem("와퍼 세트", 2, 9_500)),
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusMinutes(35),
                "서울시 강남구 선릉로 89",
                null,
                OrderStatus.CREATED
        );
    }

    private Order deliveredOrder() {
        return new Order(
                "2024-1236",
                "맘스터치 역삼점",
                List.of(new OrderItem("싸이버거 세트", 1, 8_900)),
                LocalDateTime.now().minusMinutes(60),
                LocalDateTime.now().minusMinutes(10),
                "서울시 강남구 역삼로 175",
                null,
                OrderStatus.DELIVERED
        );
    }

    private Order cookingOrder() {
        return new Order(
                "2024-1237",
                "본죽 선릉점",
                List.of(new OrderItem("전복죽", 2, 11_000)),
                LocalDateTime.now().minusMinutes(15),
                LocalDateTime.now().plusMinutes(20),
                "서울시 강남구 선릉로 100",
                null,
                OrderStatus.COOKING
        );
    }

    private Order canceledOrder() {
        var order = new Order(
                "2024-1238",
                "피자헛 강남점",
                List.of(new OrderItem("슈퍼슈프림 M", 1, 26_900)),
                LocalDateTime.now().minusMinutes(30),
                LocalDateTime.now(),
                "서울시 강남구 테헤란로 201",
                null,
                OrderStatus.CREATED
        );
        order.cancel("고객 요청", LocalDateTime.now().minusMinutes(8));

        return order;
    }
}
