package com.baedal.assistant.tool;

import com.baedal.assistant.service.OrderMockService;
import com.baedal.assistant.tool.view.CancelOrderResult;
import com.baedal.assistant.tool.view.CancelOrderResult.Outcome;
import com.baedal.support.observability.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OrderTools.cancelOrder Outcome 4분기 검증, LLM 경로 불안정에 대비한 직접 호출")
class OrderToolsCancelTest {

    private OrderTools tools;
    private OrderMockService service;

    @BeforeEach
    void setUp() {
        service = new OrderMockService();
        service.seed();
        tools = new OrderTools(service, new AgentMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("CREATED 상태 주문은 정상 취소되고 Outcome=CANCELED")
    void cancel_created() {
        CancelOrderResult result = tools.cancelOrder("2024-1235", "방금 시켰는데 메뉴를 잘못 골랐어요");

        assertEquals(Outcome.CANCELED, result.outcome());
        assertEquals("2024-1235", result.orderId());
        assertTrue(result.message().contains("취소되었습니다"));
    }

    @Test
    @DisplayName("ACCEPTED 상태 주문도 정상 취소되고 Outcome=CANCELED")
    void cancel_accepted() {
        CancelOrderResult result = tools.cancelOrder("2024-1239", "집 앞에 사람이 없어요");

        assertEquals(Outcome.CANCELED, result.outcome());
    }

    @Test
    @DisplayName("COOKING 상태는 자동 취소 불가, Outcome=NOT_CANCELABLE, 상담원 안내")
    void cancel_cooking_notCancelable() {
        CancelOrderResult result = tools.cancelOrder("2024-1237", "그냥 취소요");

        assertEquals(Outcome.NOT_CANCELABLE, result.outcome());
        assertTrue(result.message().contains("상담원"));
    }

    @Test
    @DisplayName("DELIVERED 상태도 자동 취소 불가")
    void cancel_delivered_notCancelable() {
        CancelOrderResult result = tools.cancelOrder("2024-1236", "취소");

        assertEquals(Outcome.NOT_CANCELABLE, result.outcome());
    }

    @Test
    @DisplayName("이미 취소된 주문(2024-1238)을 재취소 요청해도 에러 없이 Outcome=ALREADY_CANCELED")
    void cancel_already_canceled_isIdempotent() {
        CancelOrderResult result = tools.cancelOrder("2024-1238", "한 번 더 취소");

        assertEquals(Outcome.ALREADY_CANCELED, result.outcome());
        assertTrue(result.message().contains("이미 취소된"));
        assertTrue(result.message().contains("주소가 잘못 입력되었어요"),
                "기존 canceledReason이 덮어씌워지지 않고 보존되어야 한다");
    }

    @Test
    @DisplayName("연속 취소 요청, 첫 번째는 CANCELED, 두 번째는 ALREADY_CANCELED (멱등)")
    void cancel_twice_returnsAlreadyCanceled_onSecondCall() {
        CancelOrderResult first = tools.cancelOrder("2024-1239", "집 앞에 사람이 없어요");
        CancelOrderResult second = tools.cancelOrder("2024-1239", "한 번 더 확인 부탁드려요");

        assertEquals(Outcome.CANCELED, first.outcome());
        assertEquals(Outcome.ALREADY_CANCELED, second.outcome(),
                "두 번째 호출은 ALREADY_CANCELED 여야 한다 (에러 던지지 않음)");
        assertTrue(second.message().contains("집 앞에 사람이 없어요"),
                "첫 번째 취소의 reason이 그대로 인용되어야 한다, 두 번째 reason으로 덮어쓰면 안 됨");
    }

    @Test
    @DisplayName("존재하지 않는 주문번호는 Outcome=NOT_FOUND")
    void cancel_notFound() {
        CancelOrderResult result = tools.cancelOrder("9999-0000", "취소");

        assertEquals(Outcome.NOT_FOUND, result.outcome());
        assertTrue(result.message().contains("찾을 수 없습니다"));
    }

    @Test
    @DisplayName("orderId가 null이면 NOT_FOUND로 떨어지고 예외를 던지지 않는다")
    void cancel_nullOrderId_returnsNotFound() {
        CancelOrderResult result = tools.cancelOrder(null, "취소");

        assertEquals(Outcome.NOT_FOUND, result.outcome());
    }

    @Test
    @DisplayName("orderId가 빈 문자열이어도 NOT_FOUND로 떨어진다")
    void cancel_blankOrderId_returnsNotFound() {
        CancelOrderResult result = tools.cancelOrder("   ", "취소");

        assertEquals(Outcome.NOT_FOUND, result.outcome());
    }

    @Test
    @DisplayName("reason이 null이면 '고객 요청'으로 정규화되어 저장된다")
    void cancel_nullReason_normalizedToDefault() {
        CancelOrderResult result = tools.cancelOrder("2024-1235", null);

        assertEquals(Outcome.CANCELED, result.outcome());

        CancelOrderResult second = tools.cancelOrder("2024-1235", "다시 확인");
        assertEquals(Outcome.ALREADY_CANCELED, second.outcome());
        assertTrue(second.message().contains("고객 요청"),
                "null reason은 '고객 요청'으로 정규화되어 ALREADY_CANCELED 메시지에 인용된다");
    }

    @Test
    @DisplayName("reason이 빈 문자열이어도 '고객 요청'으로 정규화된다")
    void cancel_blankReason_normalizedToDefault() {
        CancelOrderResult result = tools.cancelOrder("2024-1239", "   ");

        assertEquals(Outcome.CANCELED, result.outcome());

        CancelOrderResult second = tools.cancelOrder("2024-1239", "확인");
        assertEquals(Outcome.ALREADY_CANCELED, second.outcome());
        assertTrue(second.message().contains("고객 요청"));
    }
}
