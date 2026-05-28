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

        // 2024-1236: 배달 완료 — 시나리오 4 (cancelOrder → NOT_CANCELABLE) 용
        save(new Order(
                "2024-1236",
                "도미노피자 역삼점",
                List.of(
                        new OrderItem("페퍼로니 피자 L", 1, 27_900),
                        new OrderItem("콜라 500ml", 2, 2_500)
                ),
                now.minusMinutes(75),
                now.minusMinutes(15),
                "서울시 강남구 역삼로 168",
                null,
                OrderStatus.DELIVERED));

        // 2024-1237: 조리 중 — cancelOrder → NOT_CANCELABLE 보조 검증용
        save(new Order(
                "2024-1237",
                "김밥천국 강남점",
                List.of(
                        new OrderItem("참치김밥", 2, 5_000),
                        new OrderItem("라면", 1, 4_500)
                ),
                now.minusMinutes(10),
                now.plusMinutes(25),
                "서울시 강남구 강남대로 396",
                null,
                OrderStatus.COOKING));

        // 2024-1238: 사전 CANCELED — 2단계 멱등성 (ALREADY_CANCELED) 핵심
        // ⚠️ cancel() 호출하지 않으면 canceledReason/canceledAt 가 null → 멱등성 실험 의미 X
        Order o1238 = new Order(
                "2024-1238",
                "BBQ치킨 강남점",
                List.of(
                        new OrderItem("황금올리브 치킨", 1, 22_000),
                        new OrderItem("콜라 1.25L", 1, 3_000)
                ),
                now.minusMinutes(30),
                now.plusMinutes(5),
                "서울시 강남구 봉은사로 213",
                null,
                OrderStatus.CANCELED);
        o1238.cancel("고객 요청", now.minusMinutes(25));   // 필수
        save(o1238);

        // 2024-1239: 사장님 수락 직후 — 2단계 (B) cancelOrder → CANCELED 정상 경로
        save(new Order(
                "2024-1239",
                "맥도날드 강남점",
                List.of(
                        new OrderItem("빅맥 세트", 1, 9_500),
                        new OrderItem("맥너겟 6조각", 1, 3_500)
                ),
                now.minusMinutes(3),
                now.plusMinutes(40),
                "서울시 강남구 테헤란로 152",
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
