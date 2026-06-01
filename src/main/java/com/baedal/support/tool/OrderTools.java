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

/**
 * 배달 상담 에이전트가 사용할 Tool 묶음.
 * <p>
 * 설계 원칙:
 * <ul>
 *     <li>@Tool의 {@code description}은 LLM이 읽는 "API 문서"다. 한국어로 명확히 작성한다.</li>
 *     <li>각 Tool은 실패 상황을 예외가 아닌 "결과 값"으로 표현한다.
 *         예외를 던지면 LLM이 Fallback할 기회를 잃는다.</li>
 *     <li>{@link #cancelOrder(String, String)}는 <b>멱등(idempotent)</b>하게 설계한다.
 *         이미 취소된 주문을 다시 취소 요청해도 동일한 성공 응답을 돌려준다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderMockService orderService;

    /**
     * 주문번호가 'YYYY-XXXX' 형식인지 검사한다.
     * 형식에 맞지 않으면 존재할 수 없는 주문번호이므로, 호출부에서 "없음"과 동일하게 처리한다.
     */
    private static final java.util.regex.Pattern ORDER_ID_FORMAT =
            java.util.regex.Pattern.compile("\\d{4}-\\d{4}");

    private boolean isValidOrderId(String orderId) {
        return orderId != null && ORDER_ID_FORMAT.matcher(orderId).matches();
    }

    @Tool(description = """
            [무엇을 하는가] 주문 상세 정보를 조회한다.
            [언제 호출하는가] 고객이 주문 메뉴, 금액, 주문 상태, 예상 배달 시간을 물을 때 호출한다. 취소 가능 여부("취소돼요?", "취소 가능해요?")를 물을 때도 취소 전 상태 확인을 위해 먼저 호출한다.
            [입력 형식] orderId 형식: 'YYYY-XXXX' (예: 2024-1234).
            [실패 반환값] 존재하지 않는 주문번호면 null을 반환한다.
            [오류 처리] 조회 중 시스템 오류가 발생하면 error 필드가 true인 응답을 반환한다. 이때는 고객에게 잠시 후 재시도나 상담사 연결을 안내한다.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "조회할 주문번호 (예: 2024-1234)") String orderId) {
        log.info("[Tool] getOrderDetail(orderId={})", orderId);

        if (!isValidOrderId(orderId)) {
            log.info("[Tool] getOrderDetail — 잘못된 주문번호 형식: {}", orderId);
            return null;
        }

        try {
            return orderService.findById(orderId)
                    .map(this::toDetailView)
                    .orElse(null);
        } catch (Exception e) {
            log.error("[Tool] getOrderDetail 실패 — orderId={}", orderId, e);
            return OrderDetailView.error(orderId);
        }
    }

    @Tool(description = """
            [무엇을 하는가] 배달 상태와 라이더 위치를 조회한다.
            [언제 호출하는가] 고객이 배달 현황이나 도착 시간을 물을 때 호출한다.
            [유효 조건] 배달 중인 주문(DELIVERING)에만 라이더 위치 정보가 유효하다.
            [입력 형식] orderId 형식: 'YYYY-XXXX' (예: 2024-1234).
            [실패 반환값] 존재하지 않는 주문번호면 null을 반환한다.
            [오류 처리] 조회 중 시스템 오류가 발생하면 error 필드가 true인 응답을 반환한다. 이때는 고객에게 잠시 후 재시도나 상담사 연결을 안내한다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "조회할 주문번호 (예: 2024-1234)") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);

        if (!isValidOrderId(orderId)) {
            log.info("[Tool] getDeliveryStatus — 잘못된 주문번호 형식: {}", orderId);
            return null;
        }

        try {
            return orderService.findById(orderId)
                    .map(this::toDeliveryView)
                    .orElse(null);
        } catch (Exception e) {
            log.error("[Tool] getDeliveryStatus 실패 — orderId={}", orderId, e);
            return DeliveryStatusView.error(orderId);
        }
    }

    @Tool(description = """
            [무엇을 하는가] 주문을 취소한다.
            [언제 호출하는가] 고객이 "취소해줘", "취소해주세요"처럼 명시적으로 주문 취소를 요청하면 즉시 호출한다.
            [취소 가능 조건] CREATED 또는 ACCEPTED 상태만 가능. COOKING 이후 상태(조리 시작됨)는 취소 불가.
            [멱등] 이미 취소된 주문을 다시 요청하면 에러가 아닌 ALREADY_CANCELED를 반환한다.
            [결과 확인] CancelOrderResult의 outcome 필드로 성공/실패 사유를 확인할 수 있다.
            """)
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "취소할 주문번호 (예: 2024-1235)") String orderId,
            @ToolParam(description = "취소 사유 (예: 단순 변심, 잘못 주문)") String reason) {
        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);

        try {
            var orderOpt = orderService.findById(orderId);
            if (orderOpt.isEmpty()) {
                return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_FOUND,
                        "주문번호 " + orderId + "를 찾을 수 없습니다.");
            }

            var order = orderOpt.get();

            if (order.status() == OrderStatus.CANCELED) {
                return new CancelOrderResult(orderId, CancelOrderResult.Outcome.ALREADY_CANCELED,
                        "이미 취소된 주문입니다. 취소 사유: " + order.canceledReason());
            }

            if (!order.isCancelable()) {
                String reasonText = switch (order.status()) {
                    case COOKING    -> "이미 조리가 시작되었습니다.";
                    case DELIVERING -> "이미 배달이 시작되었습니다.";
                    case DELIVERED  -> "이미 배달이 완료되었습니다.";
                    default         -> "현재 상태에서는 취소가 불가합니다.";
                };
                return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_CANCELABLE,
                        "현재 상태(" + order.status() + ")에서는 취소할 수 없습니다. " + reasonText);
            }

            var prevStatus = order.status();
            var prevReason = order.canceledReason();
            order.cancel(reason, LocalDateTime.now());
            log.info("[State] {} cancel: status {}→CANCELED, reason {}→{}",
                    orderId, prevStatus, prevReason, reason);

            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.CANCELED,
                    "주문이 취소되었습니다. 결제 취소는 카드사에 따라 최대 7영업일이 소요될 수 있습니다.");
        } catch (Exception e) {
            log.error("[Tool] cancelOrder 실패 — orderId={}", orderId, e);
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.ERROR,
                    "취소 처리 중 오류가 발생했습니다. 상담사에게 연결해 드리겠습니다.");
        }
    }

    private OrderDetailView toDetailView(Order order) {
        var lines = order.items().stream()
                .map(i -> new OrderDetailView.Line(i.menuName(), i.quantity(), i.unitPrice()))
                .toList();
        return new OrderDetailView(
                false,
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
        String riderLocation = order.status() == OrderStatus.DELIVERING ? order.riderLocation() : null;
        return new DeliveryStatusView(
                false,
                order.orderId(),
                order.status().name(),
                riderLocation,
                order.estimatedDeliveryAt(),
                message
        );
    }
}
