package com.baedal.support;

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
