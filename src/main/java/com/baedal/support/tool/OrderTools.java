package com.baedal.support.tool;

import com.baedal.support.domain.Order;
import com.baedal.support.domain.OrderMockService;
import com.baedal.support.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.baedal.support.tool.CancelOrderResult.*;
/**
 * 배달 상담 에이전트가 사용할 Tool 묶음.
 * <p>
 * 설계 원칙:
 * <ul>
 *     <li>@Tool의 {@code description}은 LLM이 읽는 "API 문서"다. 한국어로 명확히 작성한다.</li>
 *     <li>각 Tool은 실패 상황을 예외가 아닌 "결과 값"으로 표현한다.
 *         예외를 던지면 LLM이 Fallback할 기회를 잃는다.</li>
 *     <li>{@link #cancelOrder(String, String)}는 <b>멱등(idempotent)</b>하다.
 *         이미 취소된 주문을 다시 취소 요청해도 동일한 성공 응답을 준다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderMockService orderService;

    @Tool(description = "주문ID 로 특정 주문의 상세 정보를 조회한다. 고객이 주문 메뉴, 금액, 상태 등 주문 상세 내역을 물을 때 반드시 이 Tool 을 호출한다. 주문이 존재하지 않으면 null 을 반환한다.")
    public OrderDetailView getOrderDetail(
            @ToolParam(description="조회할 주문ID. 'YYYY-XXXX' 형식의 주문번호. 고객이 언급한 주문번호를 그대로 사용한다.") String orderId
    ) {
        return orderService.findById(orderId)
                .map(this::toDetailView)
                .orElse(null); // @Test null 로 반환하면 LLM 이 어떻게 처리하는지 테스트
    }

    @Tool(description = "주문ID 로 특정 주문의 배달 정보를 조회한다. 고객이 배달현황, 라이더 위치, 도착 예정 시간을 물을 때 사용한다. " +
                        "배달 중(DELIVERING)인 주문에서만 라이더 위치가 유효하다. 주문이 존재하지 않으면 null 을 반환한다.")
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description="조회할 주문ID. 'YYYY-XXXX' 형식의 주문번호. 고객이 언급한 주문번호를 그대로 사용한다.") String orderId
    ) {

        return orderService.findById(orderId)
                .map(this::toDeliveryView)
                // .filter(order -> order.getStatus() == OrderStatus.DELIVERING)
                .orElse(null);
    }

    @Tool(description = "고객이 주문 취소를 요청하면 반드시 이 Tool 을 호출한다. 주문 번호가 있다면 주문 상태를 먼저 확인하지 않고, 바로 이 cancelOrder 를 호출한다. " +
                        "주문ID 로 특정 주문을 취소한다. 주문 상태가 CREATED 또는 ACCEPTED인 주문에서만 취소가 가능하다.")
    public CancelOrderResult cancelOrder(
            @ToolParam(description="조회할 주문ID. 'YYYY-XXXX' 형식의 주문번호. 고객이 언급한 주문번호를 그대로 사용한다.") String orderId,
            @ToolParam(description="취소 이유. 고객이 언급한 취소사유를 그대로 사용한다. 명시적인 사유가 없으면 '고객 요청'으로 입력한다.", required = false) String reason
    ) {
        log.info("orderId : {}, reason : {}", orderId, reason);
        Order order = orderService.findById(orderId).orElse(null);

        if (order == null) {
            return new CancelOrderResult(orderId, Outcome.NOT_FOUND, "존재하지 않는 주문입니다.");
        }

        if (order.getStatus() == OrderStatus.CANCELED) {
            return new CancelOrderResult(orderId, Outcome.ALREADY_CANCELED, "이미 취소된 주문입니다.");
        }

        if (!order.isCancelable()) {
            return new CancelOrderResult(orderId, Outcome.NOT_CANCELABLE, "취소할 수 없는 주문입니다. 현재 상태: " + order.getStatus());
        }

        order.cancel(reason, LocalDateTime.now());
        return new CancelOrderResult(orderId, Outcome.CANCELED, "주문이 취소되었습니다.");
    }

    // ------- 변환기 -------

    private OrderDetailView toDetailView(Order order) {
        var lines = order.items().stream()
                .map(i -> new OrderDetailView.Line(i.menuName(), i.quantity(), i.unitPrice()))
                .toList();
        return new OrderDetailView(
                order.orderId(),
                order.storeName(),
                lines,
                order.totalAmount(),
                order.status().name(),
                order.orderedAt(),
                order.estimatedDeliveryAt()
        );
    }

    private DeliveryStatusView toDeliveryView(Order order) {
        String message = switch (order.status()) {
            case CREATED, ACCEPTED -> "아직 조리가 시작되지 않았습니다.";
            case COOKING -> "현재 조리 중입니다.";
            case DELIVERING -> "라이더가 배달 중입니다.";
            case DELIVERED -> "배달이 완료되었습니다.";
            case CANCELED -> "취소된 주문입니다.";
        };
        return new DeliveryStatusView(
                order.orderId(),
                order.status().name(),
                order.riderLocation(),
                order.estimatedDeliveryAt(),
                message
        );
    }
}
