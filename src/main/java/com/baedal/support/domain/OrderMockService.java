package com.baedal.support.domain;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 교육용 Mock 주문 저장소.
 * <p>
 * H2/JPA를 쓰지 않는 이유: 2주차 목표는 "Tool Calling 흐름의 이해"이며,
 * DB 세팅이 수강생의 주의를 분산시킨다. 메모리 Map 하나로 충분하다.
 * <p>
 * 실제 서비스에서는 이 클래스가 OrderRepository를 주입받는 OrderService가 될 것이다.
 */
@Slf4j
@Service
public class OrderMockService {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        LocalDateTime now = LocalDateTime.now();

        // 2024-1234: 배달 중 — getDeliveryStatus 호출 시 라이더 위치 확인용
        save(new Order(
                "2024-1234",
                "교촌치킨 강남점",
                List.of(
                        new OrderItem("허니콤보", 1, 23_000),
                        new OrderItem("콜라 1.25L", 1, 3_000)
                ),
                now.minusMinutes(20),
                now.plusMinutes(15),
                "서울시 강남구 테헤란로 142",
                "배달 시작 · 현재 역삼역 사거리 부근",
                OrderStatus.DELIVERING));

        // 2024-1235: 주문 직후(CREATED) — cancelOrder → CANCELED 경로용
        save(new Order(
                "2024-1235",
                "버거킹 선릉점",
                List.of(new OrderItem("와퍼 세트", 2, 9_500)),
                now.minusMinutes(5),
                now.plusMinutes(35),
                "서울시 강남구 선릉로 89",
                null,
                OrderStatus.CREATED));

        // TODO [1단계] 아래 시나리오용 Mock 데이터 4건을 추가하라.
        //
        // 왜 직접 추가해야 하는가?
        //   2단계(멱등성) / 3단계(description 실험)에서 네 가지 Outcome 경로를 모두 관찰하려면
        //   각 상태의 주문이 필요하다. "어떤 데이터가 있어야 이 Tool을 검증할 수 있는가?"를
        //   직접 판단하는 훈련이다.
        //
        // 추가해야 할 4건:
        //
        //   1) 2024-1236: OrderStatus.DELIVERED — 배달 완료된 주문
        //      용도: "이미 배달 완료된 주문의 상태 조회" 시나리오
        save(new Order(
                "2024-1236",
                "두찜 강남점",
                List.of(new OrderItem("간장찜닭", 1, 23_500)),
                now.minusMinutes(50),    // 주문시간
                now.plusMinutes(5),    // 배달시간
                "서울시 강남구 두찜로 1",
                null,
                OrderStatus.DELIVERED));
        //   2) 2024-1237: OrderStatus.COOKING — 조리 중(취소 불가) 주문
        //      용도: cancelOrder → NOT_CANCELABLE 경로 검증
        save(new Order(
                "2024-1237",
                "타코벨 강남점",
                List.of(
                        new OrderItem("치폴레 치킨 부리또", 1, 8_900),
                        new OrderItem("크런치 타코", 2, 5_500)
                ),
                now.minusMinutes(10),   // 주문시간 (10분 전에 주문)
                now.plusMinutes(30),    // 배달시간
                "서울시 강남구 타코벨로 1",
                null,
                OrderStatus.COOKING));
        //   3) 2024-1238: OrderStatus.CANCELED — 사전에 취소된 주문
        //      용도: cancelOrder → ALREADY_CANCELED 경로 검증 (멱등성 핵심)
        //
        //      ⚠️ 중요 — order.cancel(...) 호출 누락 시 2단계 멱등성 실험 실패:
        //        Order 객체를 CANCELED 상태로 만들기만 하면 canceledReason/canceledAt 필드가
        //        null로 남는다. 이 상태에서 cancelOrder Tool이 ALREADY_CANCELED를 반환해도
        //        LLM에게 "왜 취소됐는지" 정보가 없어 자연어 응답이 어색해진다.
        //        반드시 아래처럼 Order 생성 직후 cancel() 메서드를 호출하라:
        //
        //          Order o1238 = Order.of("2024-1238", ..., OrderStatus.CANCELED, ...);
        //          o1238.cancel("고객 요청", now.minusMinutes(8));   // ← 필수
        //          save(o1238);
        Order o1238 = new Order(
                "2024-1238",
                "연안식당 강남점",
                List.of(
                        new OrderItem("간장게장 정식", 1, 18_000),
                        new OrderItem("된장찌개", 1, 9_000)
                ),
                now.minusMinutes(30),   // 주문시간
                null,                   // 배달시간 (취소된 주문이므로 null)
                "서울시 강남구 연안로 1",
                null,
                OrderStatus.CANCELED);
        o1238.cancel("고객 요청", now.minusMinutes(8));
        save(o1238);
        //   4) 2024-1239: OrderStatus.ACCEPTED — 사장님 수락 직후(취소 가능)
        //      용도: cancelOrder → CANCELED 경로 검증 (수업 중 라이브 데모와 동일)
        //
        // 각 주문의 메뉴/매장명/주소는 자유롭게 정하되, 한국어 배달 톤을 유지하라.
        save(new Order(
                "2024-1239",
                "가메스시 강남점",
                List.of(
                        new OrderItem("연어 초밥 세트", 1, 22_000),
                        new OrderItem("참치 롤", 1, 12_000),
                        new OrderItem("우니 군함", 2, 8_500)
                ),
                now.minusMinutes(5),    // 주문시간 (5분 전 접수)
                now.plusMinutes(40),    // 배달 예상 시간
                "서울시 강남구 스시로 1",
                null,
                OrderStatus.ACCEPTED));

        log.info("OrderMockService seeded — {}건", orders.size());
    }

    private void save(Order order) {
        orders.put(order.orderId(), order);
    }

    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
