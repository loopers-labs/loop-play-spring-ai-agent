package com.baedal.support.controller;

import com.baedal.support.tool.CancelOrderResult;
import com.baedal.support.tool.OrderTools;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Round 2 / 2단계 검증 전용 admin 엔드포인트.
 * <p>
 * 이유: LLM 자연어 호출만으로는 cancelOrder Tool 이 결정적으로 발동되지 않음 (`qwen2.5` 한계).
 * 멱등성 분기 제거 실험·canceledReason 덮어쓰임 검증은 *결정적* 으로 수행되어야 하므로
 * Tool 메서드를 직접 호출하는 admin 엔드포인트로 LLM 우회.
 * <p>
 * LLM 응답 인용은 별도로 /api/v1/assistant 반복 호출로 확보.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {

    private final OrderTools orderTools;

    @PostMapping("/{orderId}/cancel")
    public CancelOrderResult cancel(
            @PathVariable String orderId,
            @Valid @RequestBody CancelRequest req
    ) {
        return orderTools.cancelOrder(orderId, req.reason());
    }

    public record CancelRequest(
            @NotBlank(message = "reason 은 비어 있을 수 없습니다.")
            String reason
    ) {}
}
