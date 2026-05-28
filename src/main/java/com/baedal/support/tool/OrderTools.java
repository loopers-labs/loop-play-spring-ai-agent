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

    @Tool(description = """
            주문번호로 주문 상세 정보(매장명·메뉴·금액·상태·예상 도착 시간)를 조회합니다.
            호출 시점: 고객이 자기 주문의 메뉴·금액·결제·영수증·상태 등을 물을 때 사용합니다.
            입력: orderId 는 'YYYY-XXXX' 형식의 문자열입니다. 예: '2024-1234'.
            실패: 존재하지 않는 주문번호이면 null 을 반환합니다. 추측하지 말고 본 도구로만 답하십시오.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "고객이 알려준 주문번호 (예: '2024-1234'). 'YYYY-XXXX' 형식의 문자열.")
            String orderId
    ) {
        log.info("[Tool] getOrderDetail(orderId={})", orderId);
        return orderService.findById(orderId)
                .map(this::toDetailView)
                .orElse(null);
    }

    @Tool(description = """
            주문번호로 현재 배달 진행 상태와 라이더 위치, 예상 도착 시간을 조회합니다.
            호출 시점: 고객이 '배달 어디쯤?'·'언제 도착?'·'라이더 위치' 등을 물을 때 사용합니다.
            입력: orderId 는 'YYYY-XXXX' 형식의 문자열입니다. 예: '2024-1234'.
            반환 정보: 현재 상태(예: 조리 중·배달 중·도착)와 사람이 읽기 쉬운 안내 메시지를 함께 반환합니다.
                       라이더의 정확한 위치는 배달 중(DELIVERING) 상태에서만 유효한 값이 들어 있고,
                       그 외 상태에서는 null 일 수 있습니다.
            실패: 존재하지 않는 주문번호이면 null 을 반환합니다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "고객이 알려준 주문번호 (예: '2024-1234'). 'YYYY-XXXX' 형식의 문자열.")
            String orderId
    ) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);
        return orderService.findById(orderId)
                .map(this::toDeliveryView)
                .orElse(null);
    }

    @Tool(description = """
            주문 취소 요청을 처리합니다.
            취소 가능 조건: CREATED 또는 ACCEPTED 상태에서만 가능 (조리 시작 전).
            취소 불가: COOKING 이후 상태 (조리 시작됨·배달 중·배달 완료) — Outcome NOT_CANCELABLE 반환.
            멱등성: 이미 취소된 주문(CANCELED)을 다시 요청해도 에러가 아닌 Outcome ALREADY_CANCELED 를 반환합니다.
                    같은 주문에 cancelOrder 를 여러 번 호출해도 한 번만 취소된 것과 동일한 결과를 줍니다.
            결과 타입: CancelOrderResult — outcome 필드(CANCELED / ALREADY_CANCELED / NOT_CANCELABLE / NOT_FOUND)
                       와 사람이 읽기 쉬운 message 가 함께 옵니다.
            호출 시점: 고객이 '주문 취소'·'주문 안 받을게요'·'환불해 주세요' 등을 명시할 때 사용합니다.
            """)
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "취소할 주문의 번호 (예: '2024-1239'). 'YYYY-XXXX' 형식의 문자열.")
            String orderId,
            @ToolParam(description = "고객이 알려준 취소 사유. 짧은 한국어 문장. 없으면 '고객 요청'.")
            String reason
    ) {
        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);

        var maybeOrder = orderService.findById(orderId);
        if (maybeOrder.isEmpty()) {
            return new CancelOrderResult(orderId,
                    CancelOrderResult.Outcome.NOT_FOUND,
                    "해당 주문번호를 찾을 수 없습니다.");
        }

        Order order = maybeOrder.get();

        // 멱등성 분기 — 이미 취소된 주문은 같은 응답을 재전달
        if (order.status() == OrderStatus.CANCELED) {
            return new CancelOrderResult(orderId,
                    CancelOrderResult.Outcome.ALREADY_CANCELED,
                    "이미 취소된 주문입니다. 사유: " + order.canceledReason());
        }

        if (!order.isCancelable()) {
            return new CancelOrderResult(orderId,
                    CancelOrderResult.Outcome.NOT_CANCELABLE,
                    "조리가 시작된 이후의 주문은 취소할 수 없습니다. 현재 상태: " + order.status());
        }

        order.cancel(reason, LocalDateTime.now());
        return new CancelOrderResult(orderId,
                CancelOrderResult.Outcome.CANCELED,
                "주문이 취소되었습니다. 사유: " + reason);
    }

    // ------- 변환기 (참고용 — 수정할 필요 없음) -------

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
