package com.supportplatform.whatsapp.dto;

import com.supportplatform.whatsapp.WhatsAppTemplateStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * No {@code tenantId} field: the tenant comes from the authenticated
 * Owner/Admin's security context (Rule 3).
 */
public record RegisterTemplateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull WhatsAppTemplateStatus status
) {
}
