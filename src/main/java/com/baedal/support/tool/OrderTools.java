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
            주문번호로 주문 상세(매장명, 메뉴 목록, 총액, 상태, 주문시각, 예상 도착시각)를 조회합니다.
            호출 시점: 고객이 "어떤 메뉴 시켰지?", "얼마였지?", "주문 상태가 뭐예요?" 등 주문 내역·금액·상태를 묻는 경우.
            [필수] 위 키워드가 발화에 명시되고 주문번호(YYYY-XXXX)가 있으면 반드시 이 Tool을 호출합니다. 추측·짐작으로 답하지 마세요.
            입력 형식: orderId 는 "YYYY-XXXX" (예: 2024-1234).
            반환: 존재하지 않는 주문이면 null. 이 경우 응답에서 "주문번호를 다시 확인해 주세요"라고 안내하세요.
            라이더 위치는 이 Tool이 아닌 getDeliveryStatus 를 사용하세요.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "조회할 주문번호. 형식 'YYYY-XXXX' (예: 2024-1234).") String orderId) {
        log.info("[Tool] getOrderDetail(orderId={})", orderId);
        return orderService.findById(orderId).map(this::toDetailView).orElse(null);
    }

    @Tool(description = """
            주문번호로 배달 상태와 라이더 위치를 조회합니다.
            호출 시점: 고객이 "배달 어디쯤이에요?", "라이더 어디 있어요?", "언제 도착해요?" 등 배달 진행 상황을 묻는 경우.
            [필수] 위 배달 키워드가 발화에 명시되고 주문번호(YYYY-XXXX)가 있으면 반드시 이 Tool을 호출합니다. 추측·짐작으로 답하지 마세요.
            riderLocation 필드는 status=DELIVERING 일 때만 유효하며, 그 외 상태에서는 null 또는 의미 없는 값일 수 있습니다.
            메뉴·금액 조회가 목적이면 이 Tool이 아닌 getOrderDetail 을 사용하세요.
            입력 형식: orderId 는 "YYYY-XXXX". 존재하지 않는 주문이면 null 을 반환합니다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "조회할 주문번호. 형식 'YYYY-XXXX' (예: 2024-1234).") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);
        return orderService.findById(orderId).map(this::toDeliveryView).orElse(null);
    }

    @Tool(description = """
            주문을 취소합니다.
            호출 시점: 고객이 "취소", "캔슬", "물러주세요" 같은 취소 의도 키워드로 명시적 취소를 요청할 때.
            [필수] 위 키워드가 발화에 명시되고 주문번호(YYYY-XXXX)가 있으면 반드시 이 Tool을 직접 호출합니다. getOrderDetail로 상태만 확인하고 갈음하지 마세요 — 정책 분기(NOT_CANCELABLE, ALREADY_CANCELED, NOT_FOUND 등)는 이 Tool의 outcome으로 반환됩니다. 존재하지 않을 것 같은 주문번호여도 직접 호출해 NOT_FOUND outcome을 받으세요.
            취소 가능 조건: 상태가 CREATED(주문 직후) 또는 ACCEPTED(사장님 수락 직후)일 때만 가능합니다.
            취소 불가: COOKING(조리 시작)·DELIVERING·DELIVERED 상태는 취소할 수 없습니다.
            멱등성: 이미 취소된 주문을 다시 요청하면 예외가 아니라 outcome=ALREADY_CANCELED 를 반환합니다 — 동일 요청 반복도 안전합니다.
            결과 해석(CancelOrderResult.outcome):
              CANCELED          → "주문이 취소되었습니다"
              ALREADY_CANCELED  → "이미 취소된 주문입니다" (사유가 message 에 포함되면 함께 안내)
              NOT_CANCELABLE    → "조리가 시작되어 취소할 수 없습니다"
              NOT_FOUND         → "주문번호를 찾을 수 없습니다"
            """)
    public CancelOrderResult cancelOrder(
            @ToolParam(description = "취소할 주문번호. 형식 'YYYY-XXXX' (예: 2024-1234).") String orderId,
            @ToolParam(description = "취소 사유(한국어 자연어). 고객이 사유를 말하지 않으면 '고객 요청'으로 채우세요.") String reason) {
        log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);

        var maybe = orderService.findById(orderId);
        if (maybe.isEmpty()) {
            return new CancelOrderResult(
                    orderId,
                    CancelOrderResult.Outcome.NOT_FOUND,
                    "주문번호 " + orderId + " 를 찾을 수 없습니다.");
        }
        Order order = maybe.get();

        // 멱등 분기 — 이미 CANCELED 면 동일 응답으로 안전하게 끝낸다.
        if (order.status() == OrderStatus.CANCELED) {
            String prev = order.canceledReason() != null ? order.canceledReason() : "사유 미기록";
            return new CancelOrderResult(
                    orderId,
                    CancelOrderResult.Outcome.ALREADY_CANCELED,
                    "이미 취소된 주문입니다(이전 사유: " + prev + ").");
        }

        if (!order.isCancelable()) {
            return new CancelOrderResult(
                    orderId,
                    CancelOrderResult.Outcome.NOT_CANCELABLE,
                    "현재 상태(" + order.status() + ")에서는 취소가 불가합니다. 조리 시작 이후엔 취소가 제한됩니다.");
        }

        order.cancel(reason, LocalDateTime.now());
        return new CancelOrderResult(
                orderId,
                CancelOrderResult.Outcome.CANCELED,
                "주문이 취소되었습니다(사유: " + reason + ").");
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