package com.baedal.assistant.service;

import com.baedal.assistant.domain.Order;
import com.baedal.assistant.domain.OrderItem;
import com.baedal.assistant.domain.OrderStatus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class OrderMockService {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    public void seed() {
        LocalDateTime now = LocalDateTime.now();

        put(new Order(
                "2024-1234",
                "교촌치킨 역삼점",
                List.of(
                        new OrderItem("허니콤보", 1, 23000),
                        new OrderItem("콜라 1.25L", 1, 3000)
                ),
                now.minusMinutes(25),
                now.plusMinutes(15),
                "서울시 강남구 역삼동 123-4",
                "역삼역 사거리 부근",
                OrderStatus.DELIVERING
        ));

        put(new Order(
                "2024-1235",
                "BBQ 강남점",
                List.of(new OrderItem("황금올리브", 1, 22000)),
                now.minusMinutes(2),
                now.plusMinutes(40),
                "서울시 강남구 논현동 56-7",
                null,
                OrderStatus.CREATED
        ));

        put(new Order(
                "2024-1236",
                "맥도날드 선릉점",
                List.of(
                        new OrderItem("빅맥세트", 2, 9500),
                        new OrderItem("맥너겟 6조각", 1, 4500)
                ),
                now.minusMinutes(80),
                now.minusMinutes(20),
                "서울시 강남구 대치동 11-22",
                null,
                OrderStatus.DELIVERED
        ));

        put(new Order(
                "2024-1237",
                "스시노자키",
                List.of(new OrderItem("오마카세 런치", 1, 38000)),
                now.minusMinutes(10),
                now.plusMinutes(50),
                "서울시 서초구 반포동 99-1",
                null,
                OrderStatus.COOKING
        ));

        Order canceled = new Order(
                "2024-1238",
                "도미노피자 강남점",
                List.of(new OrderItem("페퍼로니 L", 1, 27900)),
                now.minusMinutes(30),
                now.plusMinutes(15),
                "서울시 강남구 삼성동 77-8",
                null,
                OrderStatus.ACCEPTED
        );
        canceled.cancel("주소가 잘못 입력되었어요", now.minusMinutes(20));
        put(canceled);

        put(new Order(
                "2024-1239",
                "써브웨이 역삼역점",
                List.of(
                        new OrderItem("이탈리안 BMT 30cm", 1, 12900),
                        new OrderItem("쿠키", 2, 1500)
                ),
                now.minusMinutes(3),
                now.plusMinutes(35),
                "서울시 강남구 역삼동 88-9",
                null,
                OrderStatus.ACCEPTED
        ));

        log.info("OrderMockService seeded, {}건", orders.size());
    }

    private void put(Order order) {
        orders.put(order.orderId(), order);
    }

    public Optional<Order> findById(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(orders.get(orderId));
    }
}
