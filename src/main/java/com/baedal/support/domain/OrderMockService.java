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

        // 2024-1236: 배달 완료(DELIVERED) — 완료 주문 상태 조회 / cancelOrder → NOT_CANCELABLE 검증용
        save(new Order(
                "2024-1236",
                "스시로 역삼점",
                List.of(
                        new OrderItem("연어초밥 세트", 1, 18_000),
                        new OrderItem("우동", 1, 6_000)
                ),
                now.minusMinutes(70),
                now.minusMinutes(30),
                "서울시 강남구 역삼로 154",
                null,
                OrderStatus.DELIVERED));

        // 2024-1237: 조리 중(COOKING) — cancelOrder → NOT_CANCELABLE 경로 검증용
        save(new Order(
                "2024-1237",
                "마라공방 삼성점",
                List.of(new OrderItem("마라샹궈(중)", 1, 25_000)),
                now.minusMinutes(12),
                now.plusMinutes(28),
                "서울시 강남구 봉은사로 432",
                null,
                OrderStatus.COOKING));

        // 2024-1238: 사전에 취소된 주문(CANCELED) — cancelOrder → ALREADY_CANCELED(멱등성) 검증용
        // ⚠️ cancel() 을 호출해 canceledReason/canceledAt 을 채워야 LLM 응답이 자연스럽다.
        Order o1238 = new Order(
                "2024-1238",
                "도미노피자 대치점",
                List.of(new OrderItem("포테이토 피자(L)", 1, 31_900)),
                now.minusMinutes(40),
                now.plusMinutes(5),
                "서울시 강남구 도곡로 405",
                null,
                OrderStatus.CANCELED);
        o1238.cancel("고객 요청", now.minusMinutes(8));
        save(o1238);

        // 2024-1239: 사장님 수락 직후(ACCEPTED) — cancelOrder → CANCELED 경로 검증용 (라이브 데모와 동일 시드)
        save(new Order(
                "2024-1239",
                "써브웨이 선릉역점",
                List.of(
                        new OrderItem("이탈리안 BMT 15cm", 2, 6_900),
                        new OrderItem("쿠키", 2, 1_500)
                ),
                now.minusMinutes(3),
                now.plusMinutes(40),
                "서울시 강남구 테헤란로 211",
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
