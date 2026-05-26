package com.baedal.assistant.tool.view;

public record CancelOrderResult(
        String orderId,
        Outcome outcome,
        String message
) {
    public enum Outcome {
        CANCELED,
        ALREADY_CANCELED,
        NOT_CANCELABLE,
        NOT_FOUND
    }
}
