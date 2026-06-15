package com.baedal.support;

import com.baedal.support.domain.Order;
import com.baedal.support.domain.OrderMockService;
import com.baedal.support.tool.CancelOrderResult;
import com.baedal.support.tool.DeliveryStatusView;
import com.baedal.support.tool.OrderDetailView;
import com.baedal.support.tool.OrderTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolsTest {

    private OrderMockService orderService;
    private OrderTools orderTools;

    @BeforeEach
    void setUp() {
        orderService = new OrderMockService();
        orderService.seed();
        orderTools = new OrderTools(orderService);
    }

    // ── cancelOrder 4-outcome ──────────────────────────────────────

    @Test
    void cancelOrder_returns_CANCELED_for_CREATED_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1235", "배달 지연");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.CANCELED);
    }

    @Test
    void cancelOrder_returns_CANCELED_for_ACCEPTED_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1239", "집 앞에 아무도 없어요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.CANCELED);
    }

    @Test
    void cancelOrder_returns_ALREADY_CANCELED_for_pre_canceled_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1238", "재확인");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.ALREADY_CANCELED);
        assertThat(result.message()).contains("취소 사유");
    }

    @Test
    void cancelOrder_returns_NOT_CANCELABLE_for_COOKING_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1237", "취소해주세요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_CANCELABLE);
    }

    @Test
    void cancelOrder_returns_NOT_CANCELABLE_for_DELIVERED_order() {
        CancelOrderResult result = orderTools.cancelOrder("2024-1236", "취소해주세요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_CANCELABLE);
    }

    @Test
    void cancelOrder_returns_NOT_FOUND_for_unknown_orderId() {
        CancelOrderResult result = orderTools.cancelOrder("9999-0000", "취소해주세요");
        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_FOUND);
    }

    // ── 멱등성: 두 번째 취소는 상태를 변경하지 않는다 ──────────────

    @Test
    void cancelOrder_second_call_returns_ALREADY_CANCELED() {
        orderTools.cancelOrder("2024-1239", "첫 번째 취소");
        CancelOrderResult second = orderTools.cancelOrder("2024-1239", "두 번째 취소");
        assertThat(second.outcome()).isEqualTo(CancelOrderResult.Outcome.ALREADY_CANCELED);
    }

    @Test
    void cancelOrder_second_call_does_not_overwrite_canceledReason() {
        orderTools.cancelOrder("2024-1239", "첫 번째 이유");
        orderTools.cancelOrder("2024-1239", "두 번째 이유");
        Order order = orderService.findById("2024-1239").orElseThrow();
        assertThat(order.canceledReason()).isEqualTo("첫 번째 이유");
    }

    // ── getOrderDetail ─────────────────────────────────────────────

    @Test
    void getOrderDetail_returns_menu_and_amount() {
        OrderDetailView view = orderTools.getOrderDetail("2024-1234");
        assertThat(view).isNotNull();
        assertThat(view.orderId()).isEqualTo("2024-1234");
        assertThat(view.items()).isNotEmpty();
        assertThat(view.totalAmount()).isEqualTo(26000);
    }

    @Test
    void getOrderDetail_returns_null_for_unknown_orderId() {
        assertThat(orderTools.getOrderDetail("9999-0000")).isNull();
    }

    // ── getDeliveryStatus ──────────────────────────────────────────

    @Test
    void getDeliveryStatus_returns_riderLocation_for_DELIVERING_order() {
        DeliveryStatusView view = orderTools.getDeliveryStatus("2024-1234");
        assertThat(view.riderLocation()).contains("역삼역 사거리");
    }

    @Test
    void getDeliveryStatus_returns_null_riderLocation_for_non_DELIVERING_order() {
        DeliveryStatusView view = orderTools.getDeliveryStatus("2024-1235");
        assertThat(view.riderLocation()).isNull();
    }

    @Test
    void getDeliveryStatus_returns_null_for_unknown_orderId() {
        assertThat(orderTools.getDeliveryStatus("2099-9999")).isNull();
    }
}
