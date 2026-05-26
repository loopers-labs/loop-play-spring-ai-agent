package com.baedal.assistant.tool.view;

import java.time.LocalDateTime;

public record DeliveryStatusView(
        String orderId,
        String status,
        String riderLocation,
        LocalDateTime estimatedDeliveryAt
) {}
