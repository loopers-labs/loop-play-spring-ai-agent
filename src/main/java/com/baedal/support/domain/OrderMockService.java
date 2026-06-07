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

        // 2024-1236: DELIVERED — 배달 완료(조회만 가능, 취소 불가 검증용)
        save(new Order(
                "2024-1236",
                "맘스터치 역삼점",
                List.of(
                        new OrderItem("싸이순살", 1, 12_900),
                        new OrderItem("콜라 1.25L", 1, 3_000)
                ),
                now.minusHours(2),
                now.minusMinutes(50),
                "서울시 강남구 역삼로 234",
                null,
                OrderStatus.DELIVERED));

        // 2024-1237: COOKING — 조리 중(cancelOrder → NOT_CANCELABLE 검증)
        save(new Order(
                "2024-1237",
                "피자스쿨 강남점",
                List.of(new OrderItem("콤비네이션 피자(L)", 1, 19_900)),
                now.minusMinutes(10),
                now.plusMinutes(25),
                "서울시 강남구 강남대로 320",
                null,
                OrderStatus.COOKING));

        // 2024-1238: 사전 CANCELED — cancelOrder → ALREADY_CANCELED(멱등) 검증
        // 생성 직후 cancel()을 호출해 canceledReason/canceledAt까지 채워둔다.
        // (그래야 2단계 멱등 실험에서 LLM이 "왜 취소됐는지"까지 자연스럽게 안내 가능.)
        Order orderForCancel = new Order(
                "2024-1238",
                "BBQ 강남점",
                List.of(
                        new OrderItem("황금올리브 후라이드", 1, 22_000),
                        new OrderItem("치즈볼", 1, 3_500)
                ),
                now.minusMinutes(15),
                now.plusMinutes(20),
                "서울시 강남구 봉은사로 100",
                null,
                OrderStatus.CANCELED);
        orderForCancel.cancel("고객 요청", now.minusMinutes(8));
        save(orderForCancel);

        // 2024-1239: ACCEPTED — 사장님 수락 직후(cancelOrder → CANCELED 경로 검증)
        save(new Order(
                "2024-1239",
                "이삭토스트 선릉점",
                List.of(
                        new OrderItem("햄치즈 토스트", 2, 4_500),
                        new OrderItem("아메리카노", 1, 3_500)
                ),
                now.minusMinutes(3),
                now.plusMinutes(40),
                "서울시 강남구 테헤란로 411",
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