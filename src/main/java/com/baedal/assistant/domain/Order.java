package com.baedal.assistant.domain;

import java.time.LocalDateTime;
import java.util.List;

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
        this.totalAmount = items.stream().mapToInt(OrderItem::lineTotal).sum();
        this.orderedAt = orderedAt;
        this.estimatedDeliveryAt = estimatedDeliveryAt;
        this.deliveryAddress = deliveryAddress;
        this.riderLocation = riderLocation;
        this.status = status;
    }

    public void cancel(String reason, LocalDateTime at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (at == null) {
            throw new IllegalArgumentException("at must not be null");
        }
        if (this.status == OrderStatus.CANCELED) {
            return;
        }
        if (!isCancelable()) {
            throw new IllegalStateException("Order is not cancelable: " + this.status);
        }
        this.status = OrderStatus.CANCELED;
        this.canceledReason = reason;
        this.canceledAt = at;
    }

    public boolean isCancelable() {
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
