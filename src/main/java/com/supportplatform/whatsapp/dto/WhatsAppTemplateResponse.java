package com.supportplatform.whatsapp.dto;

import com.supportplatform.whatsapp.WhatsAppTemplate;
import com.supportplatform.whatsapp.WhatsAppTemplateStatus;

import java.time.Instant;
import java.util.UUID;

public record WhatsAppTemplateResponse(
        UUID id,
        String name,
        WhatsAppTemplateStatus status,
        boolean sendable,
        Instant createdAt,
        Instant updatedAt
) {

    public static WhatsAppTemplateResponse from(WhatsAppTemplate template) {
        return new WhatsAppTemplateResponse(template.getId(), template.getName(), template.getStatus(),
                template.getStatus().isSendable(), template.getCreatedAt(), template.getUpdatedAt());
    }
}
