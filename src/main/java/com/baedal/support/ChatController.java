package com.baedal.support;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final SupportService supportService;

    @PostMapping
    public String chat(@Valid @RequestBody ChatRequest request) {
        return supportService.chat(request.message());
    }
}
