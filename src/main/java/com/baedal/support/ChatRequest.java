package com.baedal.support;

public record ChatRequest(
        String systemPrompt,
        String message
) {}
