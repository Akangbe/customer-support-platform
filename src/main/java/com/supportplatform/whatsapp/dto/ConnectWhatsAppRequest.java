package com.supportplatform.whatsapp.dto;

import jakarta.validation.constraints.NotBlank;

public record ConnectWhatsAppRequest(
        @NotBlank String phoneNumberId,
        @NotBlank String wabaId,
        @NotBlank String accessToken
) {
}
