package com.baedal.support;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "message는 null·빈 문자열일 수 없습니다.")
        String message
) {}
