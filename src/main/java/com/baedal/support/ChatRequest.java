package com.baedal.support;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message) {}
