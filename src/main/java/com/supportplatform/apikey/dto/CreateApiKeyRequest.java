package com.supportplatform.apikey.dto;

import jakarta.validation.constraints.Email;
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
        @Min(1) @Max(10_000) Integer rateLimit,

        /**
         * Where to reach whoever will use this key. Optional — a key for the
         * tenant's own backend has no third party behind it — but when a key
         * is issued to a partner, this is what lets a volume alert reach them
         * the same morning it reaches the tenant, rather than by forwarded
         * email.
         */
        @Email @Size(max = 320) String contactEmail
) {
}
