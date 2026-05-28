package com.baedal.support.controller;

import com.baedal.support.dto.ChatRequest;
import com.baedal.support.service.SupportService;
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
