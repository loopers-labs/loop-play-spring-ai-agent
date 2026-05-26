package com.baedal.assistant.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Order.cancel() 도메인 가드: 호출자 검증과 무관하게 도메인 자체가 무결성을 보장한다")
class OrderTest {

    private Order accepted() {
        return new Order("2024-1000", "테스트 매장",
                List.of(new OrderItem("메뉴", 1, 10000)),
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusMinutes(30),
                "서울시 강남구",
                null,
                OrderStatus.ACCEPTED);
    }

    private Order delivered() {
        return new Order("2024-2000", "테스트 매장",
                List.of(new OrderItem("메뉴", 1, 10000)),
                LocalDateTime.now().minusMinutes(60),
                LocalDateTime.now().minusMinutes(10),
                "서울시 강남구",
                null,
                OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("ACCEPTED 상태는 정상 취소되고 status/canceledReason/canceledAt이 한 번에 설정된다")
    void cancel_accepted_transitionsCleanly() {
        Order order = accepted();
        LocalDateTime at = LocalDateTime.of(2026, 5, 23, 12, 0);

        order.cancel("집 앞에 사람이 없어요", at);

        assertEquals(OrderStatus.CANCELED, order.status());
        assertEquals("집 앞에 사람이 없어요", order.canceledReason());
        assertEquals(at, order.canceledAt());
    }

    @Test
    @DisplayName("reason이 null이면 IllegalArgumentException")
    void cancel_nullReason_throws() {
        Order order = accepted();
        assertThrows(IllegalArgumentException.class,
                () -> order.cancel(null, LocalDateTime.now()));
        assertEquals(OrderStatus.ACCEPTED, order.status(), "예외 시 status는 변경되지 않아야 한다");
    }

    @Test
    @DisplayName("reason이 빈 문자열이면 IllegalArgumentException")
    void cancel_blankReason_throws() {
        Order order = accepted();
        assertThrows(IllegalArgumentException.class,
                () -> order.cancel("   ", LocalDateTime.now()));
        assertEquals(OrderStatus.ACCEPTED, order.status());
    }

    @Test
    @DisplayName("at이 null이면 IllegalArgumentException")
    void cancel_nullAt_throws() {
        Order order = accepted();
        assertThrows(IllegalArgumentException.class,
                () -> order.cancel("사유", null));
        assertEquals(OrderStatus.ACCEPTED, order.status());
    }

    @Test
    @DisplayName("DELIVERED는 취소 불가, IllegalStateException")
    void cancel_delivered_throws() {
        Order order = delivered();
        assertThrows(IllegalStateException.class,
                () -> order.cancel("사유", LocalDateTime.now()));
        assertEquals(OrderStatus.DELIVERED, order.status(), "예외 시 status는 변경되지 않아야 한다");
    }

    @Test
    @DisplayName("이미 CANCELED인 주문 재호출은 no-op으로 떨어지고 최초 reason/canceledAt이 보존된다")
    void cancel_alreadyCanceled_isNoOpAndPreservesFirstReason() {
        Order order = accepted();
        LocalDateTime firstAt = LocalDateTime.of(2026, 5, 23, 12, 0);
        order.cancel("첫 사유", firstAt);

        order.cancel("두 번째 사유", LocalDateTime.of(2026, 5, 23, 13, 0));

        assertEquals(OrderStatus.CANCELED, order.status());
        assertEquals("첫 사유", order.canceledReason(),
                "재호출이 첫 reason을 덮어쓰면 안 된다");
        assertEquals(firstAt, order.canceledAt(),
                "재호출이 첫 canceledAt을 덮어쓰면 환불 SLA가 흔들린다");
    }

    @Test
    @DisplayName("isCancelable: CREATED/ACCEPTED는 true, 그 외는 false")
    void isCancelable_perStatus() {
        assertTrue(orderWith(OrderStatus.CREATED).isCancelable());
        assertTrue(orderWith(OrderStatus.ACCEPTED).isCancelable());
        assertFalse(orderWith(OrderStatus.COOKING).isCancelable());
        assertFalse(orderWith(OrderStatus.DELIVERING).isCancelable());
        assertFalse(orderWith(OrderStatus.DELIVERED).isCancelable());
        assertFalse(orderWith(OrderStatus.CANCELED).isCancelable());
    }

    private Order orderWith(OrderStatus status) {
        return new Order("X", "X",
                List.of(new OrderItem("X", 1, 1)),
                LocalDateTime.now(), LocalDateTime.now(), "X", null, status);
    }
}
