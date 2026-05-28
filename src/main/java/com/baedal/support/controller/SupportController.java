package com.baedal.support.controller;

import com.baedal.support.dto.ChatRequest;
import com.baedal.support.dto.SupportResponse;
import com.baedal.support.service.SupportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
public class SupportController {

    private final SupportService supportService;

    @PostMapping
    public SupportResponse triage(@Valid @RequestBody ChatRequest req) {
        return supportService.generateSupportResponse(req.message());
    }
}
