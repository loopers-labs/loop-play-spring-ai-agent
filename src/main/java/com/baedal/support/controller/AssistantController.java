package com.baedal.support.controller;

import com.baedal.support.dto.ChatRequest;
import com.baedal.support.service.SupportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling 이 적용된 자연어 응답 엔드포인트.
 * <p>
 * `/api/v1/support` 가 Structured Output(JSON)을 반환하는 데 반해,
 * 이 엔드포인트는 <b>Tool 호출의 흐름을 평문으로 관찰</b> 하기 위한 용도다.
 * <p>
 * Round 2 prompt 통합 후 — `SupportService.chat()` 을 그대로 재사용.
 * Tool 등록·ChatClient 캐싱은 SupportService 한 곳에서 관리.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final SupportService supportService;

    @PostMapping
    public String ask(@Valid @RequestBody ChatRequest req) {
        return supportService.chat(req.message());
    }
}
