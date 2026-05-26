package com.baedal.assistant.tool;

import com.baedal.assistant.service.OrderMockService;
import com.baedal.assistant.tool.view.DeliveryStatusView;
import com.baedal.assistant.tool.view.OrderDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OrderTools read 메서드 (getOrderDetail / getDeliveryStatus) 동작 검증")
class OrderToolsReadTest {

    private OrderTools tools;

    @BeforeEach
    void setUp() {
        OrderMockService service = new OrderMockService();
        service.seed();
        tools = new OrderTools(service);
    }

    @Test
    @DisplayName("getOrderDetail은 메뉴/금액/상태/시간을 View DTO로 매핑한다")
    void getOrderDetail_mapsAllFields() {
        OrderDetailView v = tools.getOrderDetail("2024-1234");

        assertNotNull(v);
        assertEquals("2024-1234", v.orderId());
        assertEquals("교촌치킨 역삼점", v.storeName());
        assertEquals(2, v.items().size());
        assertEquals("허니콤보", v.items().get(0).menuName());
        assertEquals(23000, v.items().get(0).unitPrice());
        assertEquals(26000, v.totalAmount(), "허니콤보 23000 + 콜라 3000");
        assertEquals("DELIVERING", v.status());
        assertNotNull(v.orderedAt());
        assertNotNull(v.estimatedDeliveryAt());
    }

    @Test
    @DisplayName("getOrderDetail은 존재하지 않는 주문번호에 대해 null을 돌려준다 (description 약속)")
    void getOrderDetail_unknownOrder_returnsNull() {
        assertNull(tools.getOrderDetail("9999-9999"));
    }

    @Test
    @DisplayName("getOrderDetail은 null/blank 입력에 대해서도 null을 돌려준다")
    void getOrderDetail_nullOrBlank_returnsNull() {
        assertNull(tools.getOrderDetail(null));
        assertNull(tools.getOrderDetail("   "));
    }

    @Test
    @DisplayName("DELIVERING 상태는 riderLocation이 채워진다")
    void getDeliveryStatus_delivering_includesRiderLocation() {
        DeliveryStatusView v = tools.getDeliveryStatus("2024-1234");

        assertNotNull(v);
        assertEquals("DELIVERING", v.status());
        assertEquals("역삼역 사거리 부근", v.riderLocation());
        assertNotNull(v.estimatedDeliveryAt());
    }

    @Test
    @DisplayName("COOKING 상태는 riderLocation이 null이다 (라이더가 픽업 전이라 거짓말 단서 차단)")
    void getDeliveryStatus_cooking_hidesRiderLocation() {
        DeliveryStatusView v = tools.getDeliveryStatus("2024-1237");

        assertNotNull(v);
        assertEquals("COOKING", v.status());
        assertNull(v.riderLocation(),
                "COOKING 인데 라이더 위치가 채워지면 LLM이 '라이더가 어디 있어요'에 거짓말할 단서를 받는다");
    }

    @Test
    @DisplayName("DELIVERED 상태도 riderLocation이 null이다")
    void getDeliveryStatus_delivered_hidesRiderLocation() {
        DeliveryStatusView v = tools.getDeliveryStatus("2024-1236");

        assertNotNull(v);
        assertEquals("DELIVERED", v.status());
        assertNull(v.riderLocation());
    }

    @Test
    @DisplayName("CANCELED 상태도 riderLocation이 null이다")
    void getDeliveryStatus_canceled_hidesRiderLocation() {
        DeliveryStatusView v = tools.getDeliveryStatus("2024-1238");

        assertNotNull(v);
        assertEquals("CANCELED", v.status());
        assertNull(v.riderLocation());
    }

    @Test
    @DisplayName("getDeliveryStatus도 존재하지 않는 주문번호에 대해 null을 돌려준다")
    void getDeliveryStatus_unknownOrder_returnsNull() {
        assertNull(tools.getDeliveryStatus("9999-9999"));
    }

    @Test
    @DisplayName("OrderDetailView에는 의도적으로 노출 안 한 필드가 포함되지 않는다 (record 시그니처 가드)")
    void orderDetailView_doesNotExposeForbiddenFields() {
        OrderDetailView v = tools.getOrderDetail("2024-1234");
        assertNotNull(v);

        java.lang.reflect.RecordComponent[] components = OrderDetailView.class.getRecordComponents();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (java.lang.reflect.RecordComponent c : components) {
            names.add(c.getName());
        }

        assertFalse(names.contains("deliveryAddress"),
                "deliveryAddress는 OrderDetailView에 노출되면 안 된다");
        assertFalse(names.contains("riderLocation"),
                "riderLocation은 getDeliveryStatus의 책임이므로 OrderDetailView에 노출되면 안 된다");
        assertFalse(names.contains("canceledReason"),
                "canceledReason은 cancelOrder 응답에서만 다룬다");
    }
}
