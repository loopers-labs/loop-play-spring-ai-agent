package com.baedal.assistant.tool;

import com.baedal.assistant.domain.Order;
import com.baedal.assistant.domain.OrderStatus;
import com.baedal.assistant.service.OrderMockService;
import com.baedal.assistant.tool.view.CancelOrderResult;
import com.baedal.assistant.tool.view.CancelOrderResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("단계 2 실험, 멱등성 분기 제거 시 무엇이 깨지는지 raw 관찰 (assertion 으로 회귀 방지)")
class OrderToolsIdempotencyObservationTest {

    @Test
    @DisplayName("이미 취소된 주문(2024-1238) 재취소 시 status/canceledReason/canceledAt 메타데이터가 변하지 않아야 한다")
    void alreadyCanceledOrder_metadata_isImmutableOnReCancel() {
        OrderMockService service = new OrderMockService();
        service.seed();
        OrderTools tools = new OrderTools(service);

        Order before = service.findById("2024-1238").orElseThrow();
        OrderStatus statusBefore = before.status();
        String reasonBefore = before.canceledReason();
        LocalDateTime canceledAtBefore = before.canceledAt();

        System.out.println("[before] status=" + statusBefore
                + " canceledReason=" + reasonBefore
                + " canceledAt=" + canceledAtBefore);

        CancelOrderResult r = tools.cancelOrder("2024-1238", "한 번 더 취소");
        System.out.println("[cancel re-request] outcome=" + r.outcome() + " message=" + r.message());

        Order after = service.findById("2024-1238").orElseThrow();
        System.out.println("[after]  status=" + after.status()
                + " canceledReason=" + after.canceledReason()
                + " canceledAt=" + after.canceledAt());

        assertEquals(Outcome.ALREADY_CANCELED, r.outcome(),
                "이미 취소된 주문 재호출은 ALREADY_CANCELED 여야 한다");
        assertTrue(r.message().contains("이미 취소된"),
                "사용자 안내 메시지에 '이미 취소된' 안내가 포함되어야 한다");
        assertEquals(statusBefore, after.status(), "status 가 흔들리면 안 된다");
        assertEquals(reasonBefore, after.canceledReason(),
                "canceledReason 이 두 번째 reason 으로 덮어씌워지면 안 된다");
        assertEquals(canceledAtBefore, after.canceledAt(),
                "canceledAt 이 갱신되면 환불 SLA 계산이 흔들린다, 불변이어야 한다");
    }

    @Test
    @DisplayName("정상 주문(2024-1239) 연속 취소 시 첫 reason/canceledAt 이 보존되고 두 번째는 ALREADY_CANCELED")
    void normalOrder_doubleCancel_preservesFirstCancellation() {
        OrderMockService service = new OrderMockService();
        service.seed();
        OrderTools tools = new OrderTools(service);

        CancelOrderResult first = tools.cancelOrder("2024-1239", "집 앞에 사람이 없어요");
        System.out.println("[1st] outcome=" + first.outcome() + " message=" + first.message());

        Order between = service.findById("2024-1239").orElseThrow();
        String reasonAfterFirst = between.canceledReason();
        LocalDateTime canceledAtAfterFirst = between.canceledAt();
        System.out.println("[between] status=" + between.status()
                + " canceledReason=" + reasonAfterFirst
                + " canceledAt=" + canceledAtAfterFirst);

        CancelOrderResult second = tools.cancelOrder("2024-1239", "한 번 더 확인 부탁드려요");
        System.out.println("[2nd] outcome=" + second.outcome() + " message=" + second.message());

        Order after = service.findById("2024-1239").orElseThrow();
        System.out.println("[after] status=" + after.status()
                + " canceledReason=" + after.canceledReason()
                + " canceledAt=" + after.canceledAt());

        assertEquals(Outcome.CANCELED, first.outcome());
        assertEquals(Outcome.ALREADY_CANCELED, second.outcome(),
                "두 번째 호출은 에러를 던지지 않고 ALREADY_CANCELED 로 떨어져야 한다");
        assertNotNull(reasonAfterFirst);
        assertEquals("집 앞에 사람이 없어요", reasonAfterFirst,
                "첫 호출의 reason 이 그대로 저장되어야 한다");
        assertEquals(reasonAfterFirst, after.canceledReason(),
                "두 번째 reason 으로 덮어씌워지면 안 된다");
        assertEquals(canceledAtAfterFirst, after.canceledAt(),
                "첫 취소 시각이 그대로 유지되어야 한다");
    }
}
