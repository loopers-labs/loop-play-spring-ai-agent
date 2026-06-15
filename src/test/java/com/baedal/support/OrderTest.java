package com.baedal.support;

import com.baedal.support.domain.Order;
import com.baedal.support.domain.OrderItem;
import com.baedal.support.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    private Order order(OrderStatus status) {
        return new Order("2024-0001", "테스트가게",
                List.of(new OrderItem("메뉴", 1, 10000)),
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(30),
                "서울시 강남구 테헤란로 1", null, status);
    }

    @Test
    void isCancelable_returns_true_for_CREATED() {
        assertThat(order(OrderStatus.CREATED).isCancelable()).isTrue();
    }

    @Test
    void isCancelable_returns_true_for_ACCEPTED() {
        assertThat(order(OrderStatus.ACCEPTED).isCancelable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"COOKING", "DELIVERING", "DELIVERED", "CANCELED"})
    void isCancelable_returns_false_for_non_cancelable_statuses(OrderStatus status) {
        assertThat(order(status).isCancelable()).isFalse();
    }

    @Test
    void cancel_sets_status_to_CANCELED_and_records_reason_and_time() {
        Order o = order(OrderStatus.CREATED);
        LocalDateTime at = LocalDateTime.of(2026, 5, 25, 12, 0);

        o.cancel("배달 지연", at);

        assertThat(o.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(o.canceledReason()).isEqualTo("배달 지연");
        assertThat(o.canceledAt()).isEqualTo(at);
    }
}
