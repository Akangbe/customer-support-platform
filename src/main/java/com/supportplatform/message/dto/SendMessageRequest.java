package com.supportplatform.message.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record SendMessageRequest(
        @Size(max = 4096) String body,
        String templateName,
        String templateLanguageCode,
        List<String> templateParams,
        UUID attachmentId
) {
    public SendMessageRequest {
        templateParams = templateParams == null ? List.of() : templateParams;
    }

    public SendMessageRequest(String body) {
        this(body, null, null, List.of(), null);
    }
}
