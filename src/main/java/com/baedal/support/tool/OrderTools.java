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
            주문 한 건의 상세 정보(매장명, 주문 메뉴와 수량, 총 결제금액, 주문 상태, 주문/예상도착 시각)를 조회한다.
            고객이 "뭘 주문했죠?", "얼마 나왔어요?", "지금 어떤 상태예요?"처럼 메뉴·금액·상태를 물을 때 호출한다.
            배달 위치(라이더 현황)는 이 Tool이 아니라 getDeliveryStatus 를 사용한다.
            orderId 는 "YYYY-XXXX" 형식이다 (예: 2024-1234).
            해당 주문번호가 존재하지 않으면 null 을 반환한다 — 이 경우 고객에게 주문번호 재확인을 요청하라.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "조회할 주문번호. \"YYYY-XXXX\" 형식 (예: 2024-1234)") String orderId) {
        log.info("[Tool] getOrderDetail(orderId={})", orderId);
        return orderService.findById(orderId)
                .map(this::toDetailView)
                .orElse(null);
    }

    @Tool(description = """
            주문의 배달 진행 상황(현재 상태, 라이더 위치, 예상 도착 시각)을 조회한다.
            고객이 "어디쯤 왔어요?", "언제 도착해요?", "라이더 어디 있어요?"처럼 배달 위치/도착시간을 물을 때 호출한다.
            라이더 위치(riderLocation)는 상태가 DELIVERING(배달 중)일 때만 유효하며,
            그 외 상태(조리 전/조리 중/배달 완료/취소)에서는 null 이거나 의미가 없다 — 상태값(status)을 먼저 보고 안내하라.
            orderId 는 "YYYY-XXXX" 형식이다 (예: 2024-1234).
            해당 주문번호가 존재하지 않으면 null 을 반환한다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "배달 현황을 조회할 주문번호. \"YYYY-XXXX\" 형식 (예: 2024-1234)") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);
        return orderService.findById(orderId)
                .map(this::toDeliveryView)
                .orElse(null);
    }

    @Tool(description = """
            주문을 취소한다.
            고객이 "취소해주세요", "주문 취소할게요"처럼 명시적으로 취소를 요청할 때만 호출한다.
            취소 가능 조건: 주문 상태가 CREATED(주문 직후) 또는 ACCEPTED(사장님 수락) 일 때만 가능하다.
            취소 불가: COOKING(조리 시작) 이후 상태(조리 중/배달 중/배달 완료)는 취소할 수 없다.
            멱등성: 이미 취소된 주문을 다시 취소 요청해도 에러가 아니라 ALREADY_CANCELED 를 돌려준다.
            결과는 CancelOrderResult 이며, outcome 필드(CANCELED / ALREADY_CANCELED / NOT_CANCELABLE / NOT_FOUND)를
            보고 고객에게 상황을 설명하라. 임의로 "취소되었습니다"라고 단정하지 말고 outcome 에 맞게 안내한다.
            orderId 는 "YYYY-XXXX" 형식이다 (예: 2024-1234).
            """)
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "취소할 주문번호. \"YYYY-XXXX\" 형식 (예: 2024-1234)") String orderId,
            @ToolParam(description = "취소 사유. 고객이 말한 사유를 그대로 전달하고, 없으면 \"고객 요청\"으로 둔다") String reason) {
        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);

        Order order = orderService.findById(orderId).orElse(null);
        if (order == null) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_FOUND,
                    "해당 주문번호를 찾을 수 없습니다.");
        }
        if (order.status() == OrderStatus.CANCELED) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.ALREADY_CANCELED,
                    "이미 취소된 주문입니다. (사유: " + order.canceledReason() + ")");
        }
        if (!order.isCancelable()) {
            return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_CANCELABLE,
                    "조리가 시작되어 취소할 수 없습니다. (현재 상태: " + order.status().name() + ")");
        }
        order.cancel(reason, LocalDateTime.now());
        return new CancelOrderResult(orderId, CancelOrderResult.Outcome.CANCELED,
                "주문이 취소되었습니다.");
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
