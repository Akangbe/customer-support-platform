package com.supportplatform.apikey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Deliberately has no {@code tenantId} field: the tenant a key belongs to
 * comes from the authenticated Owner/Admin's security context (Rule 3).
 */
public record CreateApiKeyRequest(
        @NotBlank @Size(max = 200) String name,
        @Min(1) @Max(10_000) Integer rateLimit
) {
}
