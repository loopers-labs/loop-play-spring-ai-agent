package com.baedal.support.tool;

import java.time.LocalDateTime;

public record DeliveryStatusView(
        String orderId,
        String status,
        String riderLocation,
        LocalDateTime estimatedDeliveryAt,
        String message
) {}
