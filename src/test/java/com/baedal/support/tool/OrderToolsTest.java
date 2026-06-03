package com.baedal.support.tool;

import com.baedal.support.domain.Order;
import com.baedal.support.domain.OrderItem;
import com.baedal.support.domain.OrderMockService;
import com.baedal.support.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderToolsTest {

    @Mock
    private OrderMockService orderService;

    @InjectMocks
    private OrderTools orderTools;

    // ──────────────────────────── getOrderDetail ────────────────────────────

    @Test
    void getOrderDetail_배달완료_주문_DELIVERED_상태_반환() {
        var items = List.of(
                new OrderItem("닭갈비", 2, 13_000),
                new OrderItem("군만두", 1, 5_000)
        );
        var order = orderForDetail("2024-1236", OrderStatus.DELIVERED, items);
        when(orderService.findById("2024-1236")).thenReturn(Optional.of(order));

        var result = orderTools.getOrderDetail("2024-1236");

        assertThat(result).isNotNull();
        assertThat(result.error()).isFalse();
        assertThat(result.orderId()).isEqualTo("2024-1236");
        assertThat(result.status()).isEqualTo("DELIVERED");
        assertThat(result.totalAmount()).isEqualTo(31_000);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void getOrderDetail_존재하지_않는_주문번호_null_반환() {
        when(orderService.findById("9999-9999")).thenReturn(Optional.empty());

        var result = orderTools.getOrderDetail("9999-9999");

        assertThat(result).isNull();
    }

    @Test
    void getOrderDetail_조회_중_예외_error_true_반환() {
        when(orderService.findById("2024-1236"))
                .thenThrow(new RuntimeException("DB 연결 실패"));

        var result = orderTools.getOrderDetail("2024-1236");

        assertThat(result).isNotNull();
        assertThat(result.error()).isTrue();
        assertThat(result.orderId()).isEqualTo("2024-1236");
    }

    @Test
    void getOrderDetail_잘못된_형식이면_조회없이_null_반환() {
        var result = orderTools.getOrderDetail("1234"); // 'YYYY-XXXX' 형식 아님

        assertThat(result).isNull();
        // 형식 단계에서 걸러졌으므로 조회 자체가 일어나지 않아야 한다.
        verify(orderService, never()).findById(any());
    }

    // ──────────────────────────── getDeliveryStatus ────────────────────────────

    @Test
    void getDeliveryStatus_배달중_주문_라이더_위치_반환() {
        var riderLocation = "역삼역 사거리 부근";
        var order = orderForDeliveryStatus("2024-1234", riderLocation);
        when(orderService.findById("2024-1234")).thenReturn(Optional.of(order));

        var result = orderTools.getDeliveryStatus("2024-1234");

        assertThat(result).isNotNull();
        assertThat(result.error()).isFalse();
        assertThat(result.orderId()).isEqualTo("2024-1234");
        assertThat(result.status()).isEqualTo("DELIVERING");
        assertThat(result.riderLocation()).isEqualTo(riderLocation);
    }

    @Test
    void getDeliveryStatus_존재하지_않는_주문번호_null_반환() {
        when(orderService.findById("9999-9999")).thenReturn(Optional.empty());

        var result = orderTools.getDeliveryStatus("9999-9999");

        assertThat(result).isNull();
    }

    @Test
    void getDeliveryStatus_조회_중_예외_error_true_반환() {
        when(orderService.findById("2024-1234"))
                .thenThrow(new RuntimeException("DB 연결 실패"));

        var result = orderTools.getDeliveryStatus("2024-1234");

        assertThat(result).isNotNull();
        assertThat(result.error()).isTrue();
        assertThat(result.orderId()).isEqualTo("2024-1234");
    }

    @Test
    void getDeliveryStatus_잘못된_형식이면_조회없이_null_반환() {
        var result = orderTools.getDeliveryStatus("abcd-1234"); // 'YYYY-XXXX' 형식 아님

        assertThat(result).isNull();
        verify(orderService, never()).findById(any());
    }

    @Test
    void getDeliveryStatus_DELIVERING_아닌_주문_riderLocation_유효하지_않음() {
        // 위치 값이 채워져 있어도, DELIVERING이 아니면 노출되지 않아야 한다.
        var order = orderForNonDeliveringStatus("2024-1234", OrderStatus.COOKING, "역삼역 부근");
        when(orderService.findById("2024-1234")).thenReturn(Optional.of(order));

        var result = orderTools.getDeliveryStatus("2024-1234");

        assertThat(result).isNotNull();
        assertThat(result.riderLocation()).isNull();
    }

    @Test
    void getDeliveryStatus_DELIVERED_주문_riderLocation_유효하지_않음() {
        // 위치 값이 채워져 있어도, DELIVERING이 아니면 노출되지 않아야 한다.
        var order = orderForNonDeliveringStatus("2024-1240", OrderStatus.DELIVERED, "고객 집 앞");
        when(orderService.findById("2024-1240")).thenReturn(Optional.of(order));

        var result = orderTools.getDeliveryStatus("2024-1240");

        assertThat(result).isNotNull();
        assertThat(result.riderLocation()).isNull();
    }

    @Test
    void getDeliveryStatus_CANCELED_주문_riderLocation_유효하지_않음() {
        // 위치 값이 채워져 있어도, DELIVERING이 아니면 노출되지 않아야 한다.
        var order = orderForNonDeliveringStatus("2024-1241", OrderStatus.CANCELED, "이전 배달 위치");
        when(orderService.findById("2024-1241")).thenReturn(Optional.of(order));

        var result = orderTools.getDeliveryStatus("2024-1241");

        assertThat(result).isNotNull();
        assertThat(result.riderLocation()).isNull();
    }

    // ──────────────────────────── cancelOrder ────────────────────────────

    @Test
    void cancelOrder_CREATED_주문_취소_성공() {
        var order = orderForCancel("2024-1235", OrderStatus.CREATED);
        when(orderService.findById("2024-1235")).thenReturn(Optional.of(order));

        var result = orderTools.cancelOrder("2024-1235", "단순 변심");

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.CANCELED);
        assertThat(result.orderId()).isEqualTo("2024-1235");
    }

    @Test
    void cancelOrder_ACCEPTED_주문_취소_성공() {
        var order = orderForCancel("2024-1239", OrderStatus.ACCEPTED);
        when(orderService.findById("2024-1239")).thenReturn(Optional.of(order));

        var result = orderTools.cancelOrder("2024-1239", "잘못 주문");

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.CANCELED);
    }

    @Test
    void cancelOrder_COOKING_주문_취소_불가() {
        var order = orderForCancel("2024-1237", OrderStatus.COOKING);
        when(orderService.findById("2024-1237")).thenReturn(Optional.of(order));

        var result = orderTools.cancelOrder("2024-1237", "단순 변심");

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_CANCELABLE);
        assertThat(result.message()).contains("이미 조리가 시작되었습니다");
    }

    @Test
    void cancelOrder_DELIVERING_주문_취소_불가_사유는_배달_시작() {
        var order = orderForCancel("2024-1242", OrderStatus.DELIVERING);
        when(orderService.findById("2024-1242")).thenReturn(Optional.of(order));

        var result = orderTools.cancelOrder("2024-1242", "단순 변심");

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_CANCELABLE);
        assertThat(result.message()).contains("이미 배달이 시작되었습니다");
    }

    @Test
    void cancelOrder_DELIVERED_주문_취소_불가_사유는_배달_완료() {
        // 배달 완료 주문에 "조리가 시작됨" 같은 부정확한 사유가 나가면 안 된다.
        var order = orderForCancel("2024-1243", OrderStatus.DELIVERED);
        when(orderService.findById("2024-1243")).thenReturn(Optional.of(order));

        var result = orderTools.cancelOrder("2024-1243", "단순 변심");

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_CANCELABLE);
        assertThat(result.message())
                .contains("이미 배달이 완료되었습니다")
                .doesNotContain("조리");
    }

    @Test
    void cancelOrder_이미_취소된_주문_멱등_응답() {
        var canceledReason = "고객 요청";
        var order = canceledOrder("2024-1238", canceledReason);
        when(orderService.findById("2024-1238")).thenReturn(Optional.of(order));

        var result = orderTools.cancelOrder("2024-1238", "재시도");

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.ALREADY_CANCELED);
        assertThat(result.message()).contains(canceledReason);
    }

    @Test
    void cancelOrder_존재하지_않는_주문번호_NOT_FOUND() {
        when(orderService.findById("9999-9999")).thenReturn(Optional.empty());

        var result = orderTools.cancelOrder("9999-9999", "사유");

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_FOUND);
    }

    @Test
    void cancelOrder_잘못된_형식이면_조회없이_NOT_FOUND_반환() {
        var result = orderTools.cancelOrder("abcd-1234", "단순 변심"); // 'YYYY-XXXX' 형식 아님

        assertThat(result.outcome()).isEqualTo(CancelOrderResult.Outcome.NOT_FOUND);
        verify(orderService, never()).findById(any());
    }

    // ──────────────────────────── Fixtures ────────────────────────────

    private Order orderForCancel(String orderId, OrderStatus status) {
        return new Order(
                orderId,
                "테스트 매장",
                List.of(new OrderItem("테스트 메뉴", 1, 10_000)),
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().plusMinutes(20),
                "서울시 강남구",
                null,
                status
        );
    }

    private Order canceledOrder(String orderId, String canceledReason) {
        var order = new Order(
                orderId,
                "테스트 매장",
                List.of(new OrderItem("테스트 메뉴", 1, 10_000)),
                LocalDateTime.now().minusMinutes(15),
                LocalDateTime.now().plusMinutes(5),
                "서울시 강남구",
                null,
                OrderStatus.CREATED
        );
        order.cancel(canceledReason, LocalDateTime.now().minusMinutes(8));
        return order;
    }

    private Order orderForDeliveryStatus(String orderId, String riderLocation) {
        return new Order(
                orderId,
                "테스트 매장",
                List.of(new OrderItem("테스트 메뉴", 1, 10_000)),
                LocalDateTime.now().minusMinutes(20),
                LocalDateTime.now().plusMinutes(10),
                "서울시 강남구",
                riderLocation,
                OrderStatus.DELIVERING
        );
    }

    private Order orderForNonDeliveringStatus(String orderId, OrderStatus status) {
        return orderForNonDeliveringStatus(orderId, status, null);
    }

    private Order orderForNonDeliveringStatus(String orderId, OrderStatus status, String riderLocation) {
        return new Order(
                orderId,
                "테스트 매장",
                List.of(new OrderItem("테스트 메뉴", 1, 10_000)),
                LocalDateTime.now().minusMinutes(20),
                LocalDateTime.now().plusMinutes(10),
                "서울시 강남구",
                riderLocation,
                status
        );
    }

    private Order orderForDetail(String orderId, OrderStatus status, List<OrderItem> items) {
        return new Order(
                orderId,
                "테스트 매장",
                items,
                LocalDateTime.now().minusMinutes(30),
                LocalDateTime.now().plusMinutes(5),
                "서울시 강남구",
                null,
                status
        );
    }
}