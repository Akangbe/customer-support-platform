package com.supportplatform.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SendMessageRequest(
        @NotBlank @Size(max = 4096) String body,
        String templateName,
        String templateLanguageCode,
        List<String> templateParams
) {
    public SendMessageRequest {
        templateParams = templateParams == null ? List.of() : templateParams;
    }

    public SendMessageRequest(String body) {
        this(body, null, null, List.of());
    }
}
