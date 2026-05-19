package com.baedal.support;

public record PromptLabRequest(
        String systemPrompt,
        String message,
        int repeat,
        Double temperature,
        EvalCriteria evalCriteria
) {}
