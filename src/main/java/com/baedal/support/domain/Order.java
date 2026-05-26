package com.baedal.support.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mock 주문 엔티티. 실제 서비스라면 JPA Entity가 되겠지만,
 * 2주차 수업에서는 Map 기반 Mock으로 충분하다.
 * <p>
 * {@code status}와 {@code canceledReason}은 취소 처리 시 갱신되므로
 * 이 클래스는 record가 아닌 일반 클래스로 만들었다.
 */
public class Order {

    private final String orderId;
    private final String storeName;
    private final List<OrderItem> items;
    private final int totalAmount;
    private final LocalDateTime orderedAt;
    private final LocalDateTime estimatedDeliveryAt;
    private final String deliveryAddress;
    private final String riderLocation;

    private OrderStatus status;
    private String canceledReason;
    private LocalDateTime canceledAt;

    public Order(String orderId,
                 String storeName,
                 List<OrderItem> items,
                 LocalDateTime orderedAt,
                 LocalDateTime estimatedDeliveryAt,
                 String deliveryAddress,
                 String riderLocation,
                 OrderStatus status) {
        this.orderId = orderId;
        this.storeName = storeName;
        this.items = List.copyOf(items);
        this.totalAmount = items.stream().mapToInt(OrderItem::totalPrice).sum();
        this.orderedAt = orderedAt;
        this.estimatedDeliveryAt = estimatedDeliveryAt;
        this.deliveryAddress = deliveryAddress;
        this.riderLocation = riderLocation;
        this.status = status;
    }

    public void cancel(String reason, LocalDateTime at) {
        this.status = OrderStatus.CANCELED;
        this.canceledReason = reason;
        this.canceledAt = at;
    }

    public boolean isCancelable() {
        // 조리가 시작되기 전까지만 취소 허용
        return status == OrderStatus.CREATED || status == OrderStatus.ACCEPTED;
    }

    public String orderId() { return orderId; }
    public String storeName() { return storeName; }
    public List<OrderItem> items() { return items; }
    public int totalAmount() { return totalAmount; }
    public LocalDateTime orderedAt() { return orderedAt; }
    public LocalDateTime estimatedDeliveryAt() { return estimatedDeliveryAt; }
    public String deliveryAddress() { return deliveryAddress; }
    public String riderLocation() { return riderLocation; }
    public OrderStatus status() { return status; }
    public String canceledReason() { return canceledReason; }
    public LocalDateTime canceledAt() { return canceledAt; }
}
