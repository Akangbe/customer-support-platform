package com.supportplatform.whatsapp.dto;

import jakarta.validation.constraints.NotBlank;

public record EmbeddedSignupRequest(
        @NotBlank String code,
        @NotBlank String phoneNumberId,
        @NotBlank String wabaId
) {
}
