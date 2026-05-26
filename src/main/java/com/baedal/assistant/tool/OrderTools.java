package com.baedal.assistant.tool;

import com.baedal.assistant.domain.Order;
import com.baedal.assistant.domain.OrderStatus;
import com.baedal.assistant.service.OrderMockService;
import com.baedal.assistant.tool.view.CancelOrderResult;
import com.baedal.assistant.tool.view.CancelOrderResult.Outcome;
import com.baedal.assistant.tool.view.DeliveryStatusView;
import com.baedal.assistant.tool.view.OrderDetailView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderMockService orderService;

    @Tool(description = """
            주어진 주문번호의 상세 정보를 조회한다.
            매장명, 메뉴와 수량, 단가, 총 결제 금액, 현재 주문 상태, 주문 시각,
            예상 배달 완료 시각을 함께 반환한다.
            사용자가 "어떤 메뉴 시켰는지", "얼마였는지", "주문한 게 뭐였는지" 와 같이
            주문 내용을 묻거나 결제 금액을 확인하려 할 때 호출한다.
            주문번호 형식은 "YYYY-XXXX" 이며 (예: 2024-1234),
            존재하지 않으면 null을 반환한다.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "조회할 주문번호. 예: 2024-1234") String orderId) {
        log.info("[Tool] getOrderDetail(orderId={})", maskOrderId(orderId));
        return orderService.findById(orderId).map(this::toDetailView).orElse(null);
    }

    @Tool(description = """
            주어진 주문번호의 현재 배달 상태와 라이더의 대략적인 위치를 조회한다.
            사용자가 "지금 어디쯤이에요", "라이더가 어디 있어요", "언제 도착해요"
            처럼 배달 진행 상황을 물을 때 호출한다.
            배달 중(DELIVERING)인 주문에 한해 riderLocation이 채워지며,
            그 외 상태(CREATED, ACCEPTED, COOKING, DELIVERED, CANCELED)에서는 null이다.
            존재하지 않는 주문번호면 null을 반환한다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "배달 상태를 조회할 주문번호. 예: 2024-1234") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", maskOrderId(orderId));
        return orderService.findById(orderId).map(this::toDeliveryView).orElse(null);
    }

    @Tool(description = """
            주어진 주문번호의 주문을 취소한다.
            취소 가능 조건: 주문 상태가 CREATED(접수 대기) 또는 ACCEPTED(매장 수락) 인 경우에만 자동 취소된다.
            조리가 시작된 이후(COOKING, DELIVERING, DELIVERED) 의 주문은 자동 취소가 불가능하며
            NOT_CANCELABLE 결과로 상담사 연결을 안내해야 한다.
            이미 취소된 주문을 다시 취소 요청해도 에러를 던지지 않고 ALREADY_CANCELED 결과를 반환한다(멱등).
            존재하지 않는 주문번호면 NOT_FOUND를 반환한다.
            결과는 항상 CancelOrderResult로 돌아오며, outcome 필드를 보고 사용자에게 어떻게 안내할지 결정한다.
            """)
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "취소할 주문번호. 예: 2024-1239") String orderId,
            @ToolParam(description = "고객이 말한 취소 사유. 예: '집 앞에 사람이 없어요'") String reason) {
        String normalizedReason = (reason == null || reason.isBlank()) ? "고객 요청" : reason.trim();
        log.info("[Tool] cancelOrder(orderId={}, reasonLength={})",
                maskOrderId(orderId), normalizedReason.length());

        Order order = orderService.findById(orderId).orElse(null);
        if (order == null) {
            return new CancelOrderResult(orderId, Outcome.NOT_FOUND,
                    "해당 주문번호를 찾을 수 없습니다.");
        }

        if (order.status() == OrderStatus.CANCELED) {
            return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED,
                    "해당 주문은 이미 취소된 상태입니다. (취소 사유: " + order.canceledReason() + ")");
        }

        if (!order.isCancelable()) {
            return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE,
                    "조리가 이미 시작되어(" + order.status() + ") 자동 취소가 불가합니다. 상담원 연결이 필요합니다.");
        }

        order.cancel(normalizedReason, LocalDateTime.now());
        return new CancelOrderResult(orderId, Outcome.CANCELED,
                "주문이 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있습니다.");
    }

    private OrderDetailView toDetailView(Order order) {
        return new OrderDetailView(
                order.orderId(),
                order.storeName(),
                order.items().stream()
                        .map(i -> new OrderDetailView.Line(i.menuName(), i.quantity(), i.unitPrice()))
                        .toList(),
                order.totalAmount(),
                order.status().name(),
                order.orderedAt(),
                order.estimatedDeliveryAt()
        );
    }


    private DeliveryStatusView toDeliveryView(Order order) {
        String rider = order.status() == OrderStatus.DELIVERING ? order.riderLocation() : null;
        return new DeliveryStatusView(
                order.orderId(),
                order.status().name(),
                rider,
                order.estimatedDeliveryAt()
        );
    }

    static String maskOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return "null";
        }
        if (orderId.length() <= 4) {
            return "***";
        }
        return orderId.substring(0, 4) + "-***";
    }
}
