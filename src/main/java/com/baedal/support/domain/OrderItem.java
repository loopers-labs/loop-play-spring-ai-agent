package com.baedal.support.domain;

public record OrderItem(
        String menuName,
        int quantity,
        int unitPrice
) {
    public int totalPrice() {
        return unitPrice * quantity;
    }
}
