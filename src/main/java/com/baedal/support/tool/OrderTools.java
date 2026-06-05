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
 *     <li>{@link #cancelOrder(String, String)}는 <b>멱등(idempotent)</b>하게 설계한다.
 *         이미 취소된 주문을 다시 취소 요청해도 동일한 성공 응답을 돌려준다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderMockService orderService;

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

    // @Test 어떤 방식으로 변경해서 테스트를 해봐야 할지 고민
    // 1. 입력 형식이 있다고 차이가 있는가?
    // 2. 실패 시 반환값이 없다고 차이가 있는가?
    @Tool(description = "주문ID 로 특정 주문의 상세 정보를 조회한다. 고객이 주문 메뉴, 금액, 상태 등 주문 상세 내역을 물을 때 반드시 이 Tool 을 호출한다. 주문이 존재하지 않으면 null 을 반환한다.")
    public OrderDetailView getOrderDetail(
            @ToolParam(description="조회할 주문ID. 'YYYY-XXXX' 형식의 주문번호. 고객이 언급한 주문번호를 그대로 사용한다.") String orderId
    ) {
        return orderService.findById(orderId)
                .map(this::toDetailView)
                .orElse(null); // @Test null 로 반환하면 LLM 이 어떻게 처리하는지 테스트
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

    // @Test 1. 자동으로 주문상태를 확인하는가? 여러 번 테스트 (주문 상태가 불확실한 경우 먼저 getOrder로 상태를 확인한 후 호출하라. 추가해서 다시 테스트)
    // 2. LLM 이 먼저 호출해보고 이 메소드를 호출하도록? 혹은 개발자가 직접 코드 상에 명시?
    // 		배달 상태 조회는 항상 주문 정보가 필요하다. 그렇다면 LLM 에게 호출을 맡길 필요가 있나?
    @Tool(description = "주문ID 로 특정 주문의 배달 정보를 조회한다. 고객이 배달현황, 라이더 위치, 도착 예정 시간을 물을 때 사용한다. " +
                        "배달 중(DELIVERING)인 주문에서만 라이더 위치가 유효하다. 주문이 존재하지 않으면 null 을 반환한다.")
    public DeliveryStatusView getDeliveryStatus(
            @ToolParam(description="조회할 주문ID. 'YYYY-XXXX' 형식의 주문번호. 고객이 언급한 주문번호를 그대로 사용한다.") String orderId
    ) {
        return orderService.findById(orderId)
                .map(this::toDeliveryView)
                .orElse(null);
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
    // @Test 1. 취소 가능 조건 / 불가 조건이 모두 있을 필요가 있나? (여러 번 테스트 해서 확인)
    // 2. 위 과제 확인
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
