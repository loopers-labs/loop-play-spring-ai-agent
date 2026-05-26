package com.baedal.assistant.domain;

public record OrderItem(String menuName, int quantity, int unitPrice) {
    public int lineTotal() {
        return quantity * unitPrice;
    }
}
