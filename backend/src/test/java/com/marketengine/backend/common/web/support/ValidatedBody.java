package com.marketengine.backend.common.web.support;

import jakarta.validation.constraints.NotBlank;

public record ValidatedBody(@NotBlank String name) {
}
