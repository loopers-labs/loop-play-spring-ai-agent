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

        save(new Order(
                "2024-1235",
                "버거킹 선릉점",
                List.of(new OrderItem("와퍼 세트", 2, 9_500)),
                now.minusMinutes(5),
                now.plusMinutes(35),
                "서울시 강남구 선릉로 89",
                null,
                OrderStatus.CREATED));

        save(new Order(
                "2024-1236",
                "스시로 서초점",
                List.of(
                        new OrderItem("모둠 초밥", 1, 28_000),
                        new OrderItem("연어 롤", 1, 12_000)
                ),
                now.minusMinutes(45),
                now.minusMinutes(5),
                "서울시 서초구 강남대로 465",
                null,
                OrderStatus.DELIVERED));

        save(new Order(
                "2024-1237",
                "마라탕후루 역삼점",
                List.of(new OrderItem("마라탕 중 (매운맛)", 1, 14_000)),
                now.minusMinutes(12),
                now.plusMinutes(25),
                "서울시 강남구 역삼로 123",
                null,
                OrderStatus.COOKING));

        save(new Order(
                "2024-1238",
                "맥도날드 삼성점",
                List.of(
                        new OrderItem("빅맥 세트", 1, 7_500),
                        new OrderItem("애플파이", 2, 1_800)
                ),
                now.minusMinutes(30),
                now.minusMinutes(10),
                "서울시 강남구 삼성로 212",
                null,
                OrderStatus.CANCELED));
        // 2024-1238은 사전에 취소된 상태 — 멱등성 시나리오 확인용

        save(new Order(
                "2024-1239",
                "요아정 강남역점",
                List.of(new OrderItem("플레인 요거트 + 그래놀라", 1, 9_800)),
                now.minusMinutes(2),
                now.plusMinutes(28),
                "서울시 강남구 강남대로 396",
                null,
                OrderStatus.ACCEPTED));

        // 2024-1236: 배달 완료 — getOrderDetail / getDeliveryStatus 완료 상태 조회용
        save(new Order(
                "2024-1236",
                "맘스터치 역삼점",
                List.of(
                        new OrderItem("싸이버거 세트", 1, 8_900),
                        new OrderItem("어니언링", 1, 2_500)
                ),
                now.minusMinutes(60),
                now.minusMinutes(10),
                "서울시 강남구 역삼로 175",
                null,
                OrderStatus.DELIVERED));

        // 2024-1237: 조리 중 — cancelOrder → NOT_CANCELABLE 경로 검증
        save(new Order(
                "2024-1237",
                "본죽 선릉점",
                List.of(new OrderItem("전복죽", 2, 11_000)),
                now.minusMinutes(15),
                now.plusMinutes(20),
                "서울시 강남구 선릉로 100",
                null,
                OrderStatus.COOKING));

        // 2024-1238: 사전 취소 — cancelOrder → ALREADY_CANCELED 경로 검증 (멱등성)
        var o1238 = new Order(
                "2024-1238",
                "피자헛 강남점",
                List.of(new OrderItem("슈퍼슈프림 M", 1, 26_900)),
                now.minusMinutes(30),
                now.plusMinutes(0),
                "서울시 강남구 테헤란로 201",
                null,
                OrderStatus.CREATED);
        o1238.cancel("고객 요청", now.minusMinutes(8));
        save(o1238);

        // 2024-1239: 사장님 수락 직후 — cancelOrder → CANCELED 경로 검증 (라이브 데모)
        save(new Order(
                "2024-1239",
                "이삭토스트 강남역점",
                List.of(
                        new OrderItem("야채 토스트", 2, 4_500),
                        new OrderItem("아이스 아메리카노", 2, 2_500)
                ),
                now.minusMinutes(8),
                now.plusMinutes(27),
                "서울시 강남구 강남대로 396",
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
