package com.baedal.assistant.service;

import com.baedal.assistant.domain.Order;
import com.baedal.assistant.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OrderMockService seed / findById 동작 검증")
class OrderMockServiceTest {

    private OrderMockService service;

    @BeforeEach
    void setUp() {
        service = new OrderMockService();
        service.seed();
    }

    @Test
    @DisplayName("seed가 6건의 주문을 단계 2 Outcome 4분기를 모두 커버하도록 분포시켰다")
    void seed_distributesAllOutcomeBranches() {
        assertEquals(OrderStatus.DELIVERING, service.findById("2024-1234").orElseThrow().status());
        assertEquals(OrderStatus.CREATED,    service.findById("2024-1235").orElseThrow().status());
        assertEquals(OrderStatus.DELIVERED,  service.findById("2024-1236").orElseThrow().status());
        assertEquals(OrderStatus.COOKING,    service.findById("2024-1237").orElseThrow().status());
        assertEquals(OrderStatus.CANCELED,   service.findById("2024-1238").orElseThrow().status());
        assertEquals(OrderStatus.ACCEPTED,   service.findById("2024-1239").orElseThrow().status());
    }

    @Test
    @DisplayName("사전 취소된 2024-1238은 canceledReason과 canceledAt이 모두 채워져 있다 (ALREADY_CANCELED 시나리오 전제)")
    void seed_preCanceledOrder_hasReasonAndTimestamp() {
        Order canceled = service.findById("2024-1238").orElseThrow();

        assertNotNull(canceled.canceledReason(),
                "canceledReason이 비어있으면 ALREADY_CANCELED 메시지에서 사유 인용이 깨진다");
        assertEquals("주소가 잘못 입력되었어요", canceled.canceledReason());
        assertNotNull(canceled.canceledAt());
    }

    @Test
    @DisplayName("DELIVERING 주문(2024-1234)만 riderLocation이 채워져 있다")
    void seed_riderLocation_onlyForDelivering() {
        Order delivering = service.findById("2024-1234").orElseThrow();
        assertEquals("역삼역 사거리 부근", delivering.riderLocation());

        for (String id : new String[] {"2024-1235", "2024-1236", "2024-1237", "2024-1238", "2024-1239"}) {
            assertNull(service.findById(id).orElseThrow().riderLocation(),
                    id + " 의 riderLocation은 시드 시점부터 null 이어야 한다");
        }
    }

    @Test
    @DisplayName("findById는 존재하지 않는 ID에 대해 Optional.empty()를 돌려준다")
    void findById_unknown_returnsEmpty() {
        assertTrue(service.findById("9999-9999").isEmpty());
    }

    @Test
    @DisplayName("findById는 null/blank/whitespace에 대해 NPE 없이 Optional.empty()를 돌려준다")
    void findById_nullOrBlank_returnsEmpty() {
        assertTrue(service.findById(null).isEmpty());
        assertTrue(service.findById("").isEmpty());
        assertTrue(service.findById("   ").isEmpty());
    }
}
