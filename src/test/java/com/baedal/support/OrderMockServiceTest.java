package com.baedal.support;

import com.baedal.support.domain.Order;
import com.baedal.support.domain.OrderMockService;
import com.baedal.support.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMockServiceTest {

    private OrderMockService service;

    @BeforeEach
    void setUp() {
        service = new OrderMockService();
        service.seed();
    }

    @Test
    void seed_creates_all_6_orders() {
        assertThat(service.findById("2024-1234")).isPresent();
        assertThat(service.findById("2024-1235")).isPresent();
        assertThat(service.findById("2024-1236")).isPresent();
        assertThat(service.findById("2024-1237")).isPresent();
        assertThat(service.findById("2024-1238")).isPresent();
        assertThat(service.findById("2024-1239")).isPresent();
    }

    @Test
    void order_2024_1234_is_DELIVERING_with_riderLocation() {
        Order o = service.findById("2024-1234").orElseThrow();
        assertThat(o.status()).isEqualTo(OrderStatus.DELIVERING);
        assertThat(o.riderLocation()).contains("역삼역 사거리");
    }

    @Test
    void order_2024_1238_is_pre_canceled_with_reason() {
        Order o = service.findById("2024-1238").orElseThrow();
        assertThat(o.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(o.canceledReason()).isNotBlank();
        assertThat(o.canceledAt()).isNotNull();
    }

    @Test
    void findById_returns_empty_for_unknown_orderId() {
        assertThat(service.findById("9999-0000")).isEmpty();
    }
}
