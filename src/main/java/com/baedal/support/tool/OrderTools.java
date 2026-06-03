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

    /** 주문번호 형식: 'YYYY-XXXX' (예: 2024-1234). */
    private static final java.util.regex.Pattern ORDER_ID_FORMAT =
            java.util.regex.Pattern.compile("\\d{4}-\\d{4}");

    /**
     * 주문번호가 'YYYY-XXXX' 형식인지 검사한다.
     * 형식에 맞지 않으면 존재할 수 없는 주문번호이므로, 호출부에서 "없음"과 동일하게 처리한다.
     */
    private boolean isValidOrderId(String orderId) {
        return orderId != null && ORDER_ID_FORMAT.matcher(orderId).matches();
    }

    // TODO [1단계-1] getOrderDetail Tool을 구현하라.
    //
    // 요구사항:
    // - 메서드 위에 @Tool(description = "...") 을 달고, LLM이 읽을 한국어 설명을 작성한다.
    //   description에는 최소 다음 4가지가 들어가야 한다:
    //     (1) 무엇을 하는가
    //     (2) 언제 호출해야 하는가 (예: 고객이 메뉴/금액/상태를 물을 때)
    //     (3) 입력(orderId)의 형식 — 예: "YYYY-XXXX" (예: 2024-1234)
    //     (4) 실패 시 반환값 — 존재하지 않는 주문번호면 null 반환
    // - 파라미터에 @ToolParam(description = "...") 을 달아 한국어 설명을 작성한다.
    // - log.info("[Tool] getOrderDetail(orderId={})", orderId); 로 호출을 로깅한다.
    // - orderService.findById(orderId) 로 조회하여, 존재하면 toDetailView()로 변환, 없으면 null.
    //
    // 힌트: toDetailView(Order) 변환기는 아래에 이미 준비되어 있다.
    @Tool(description = """
            주문 상세 정보를 조회한다.
            고객이 주문 메뉴, 금액, 주문 상태, 예상 배달 시간을 물을 때 호출한다.
            orderId 형식: 'YYYY-XXXX' (예: 2024-1234).
            존재하지 않는 주문번호면 null을 반환한다.
            조회 중 시스템 오류가 발생하면 error 필드가 true인 응답을 반환한다.
            이때는 고객에게 잠시 후 재시도나 상담사 연결을 안내한다.
            """)
    public OrderDetailView getOrderDetail(
            @ToolParam(description = "조회할 주문번호 (예: 2024-1234)") String orderId) {
        log.info("[Tool] getOrderDetail(orderId={})", orderId);

        // 형식이 'YYYY-XXXX'가 아니면 존재할 수 없는 주문번호이므로 "없음"과 동일하게 null 반환.
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

    // TODO [1단계-2] getDeliveryStatus Tool을 구현하라.
    //
    // 요구사항:
    // - @Tool(description = "...") 에 "배달 중인 주문에만 라이더 위치가 유효함"을 명시한다.
    // - @ToolParam(description = "...") 을 추가한다.
    // - log.info("[Tool] getDeliveryStatus(orderId={})", orderId);
    // - 존재하면 toDeliveryView()로 변환, 없으면 null 반환.
    //
    // 힌트: toDeliveryView(Order) 변환기는 아래에 이미 준비되어 있다.
    @Tool(description = """
            배달 상태와 라이더 위치를 조회한다.
            고객이 배달 현황이나 도착 시간을 물을 때 호출한다.
            배달 중인 주문(DELIVERING)에만 라이더 위치 정보가 유효하다.
            orderId 형식: 'YYYY-XXXX' (예: 2024-1234).
            존재하지 않는 주문번호면 null을 반환한다.
            조회 중 시스템 오류가 발생하면 error 필드가 true인 응답을 반환한다.
            이때는 고객에게 잠시 후 재시도나 상담사 연결을 안내한다.
            """)
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description = "조회할 주문번호 (예: 2024-1234)") String orderId) {
        log.info("[Tool] getDeliveryStatus(orderId={})", orderId);

        // 형식이 'YYYY-XXXX'가 아니면 존재할 수 없는 주문번호이므로 "없음"과 동일하게 null 반환.
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

    // TODO [1단계-3] + [2단계] cancelOrder Tool을 구현하라.
    //
    // 1단계 요구사항:
    // - @Tool(description = "...") 에 다음을 모두 포함한다:
    //     (1) 취소 가능 조건: CREATED 또는 ACCEPTED 상태만 가능
    //     (2) 취소 불가: COOKING 이후 상태 (조리 시작됨)
    //     (3) 멱등성 안내: 이미 취소된 주문을 다시 요청하면 에러가 아닌 ALREADY_CANCELED 반환
    //     (4) 결과 타입: CancelOrderResult (outcome 필드로 성공/실패 사유 확인)
    // - @ToolParam 2개 (orderId, reason) 각각 한국어 설명.
    // - log.info("[Tool] cancelOrder(orderId={}, reason={})", orderId, reason);
    //
    // 로직 분기 (Outcome 4가지 — CancelOrderResult.Outcome 참조):
    //   1) 주문 없음                     → NOT_FOUND       (예외 대신 결과 값으로)
    //   2) 이미 CANCELED 상태            → ALREADY_CANCELED (멱등성 핵심)
    //   3) isCancelable() == false       → NOT_CANCELABLE  (COOKING/DELIVERING/DELIVERED)
    //   4) 취소 가능                     → order.cancel(reason, LocalDateTime.now()) 후 CANCELED
    //
    // 2단계 추가 과제 (README에 관찰 기록):
    // - 같은 orderId로 cancelOrder를 연속 2회 호출했을 때 1번째/2번째 응답 비교.
    // - 멱등성 분기(이미 CANCELED 처리)를 "통째로 제거"한 버전을 한 번 돌려보고,
    //   LLM의 응답이 어떻게 달라지는지 관찰한다.
    @Tool(description = """
            주문을 취소한다.
            취소 가능 조건: CREATED 또는 ACCEPTED 상태만 가능.
            COOKING 이후 상태(조리 시작됨)는 취소 불가.
            이미 취소된 주문을 다시 요청하면 에러가 아닌 ALREADY_CANCELED를 반환한다(멱등).
            결과는 CancelOrderResult의 outcome 필드로 성공/실패 사유를 확인할 수 있다.
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
                // 취소 불가 사유는 상태별로 분기한다. (CREATED/ACCEPTED는 cancelable, CANCELED는 위에서 처리)
                String reasonText = switch (order.status()) {
                    case COOKING    -> "이미 조리가 시작되었습니다.";
                    case DELIVERING -> "이미 배달이 시작되었습니다.";
                    case DELIVERED  -> "이미 배달이 완료되었습니다.";
                    default         -> "현재 상태에서는 취소가 불가합니다.";
                };
                return new CancelOrderResult(orderId, CancelOrderResult.Outcome.NOT_CANCELABLE,
                        "현재 상태(" + order.status() + ")에서는 취소할 수 없습니다. " + reasonText);
            }

            // 상태 변경 직전/직후 값을 함께 남긴다. status CANCELED→CANCELED 또는
            // reason A→B 가 보이면 멱등성 위반(재취소)·취소 사유 덮어쓰임의 증거가 된다.
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

    // ------- 변환기 (참고용 — 수정할 필요 없음) -------

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
        // 라이더 위치는 배달 중(DELIVERING)에만 유효하다. 그 외 상태에서는 데이터 유무와 무관하게 노출하지 않는다.
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
