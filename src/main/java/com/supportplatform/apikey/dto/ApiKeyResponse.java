package com.supportplatform.apikey.dto;

import com.supportplatform.apikey.ApiKey;

import java.time.Instant;
import java.util.UUID;

/**
 * The safe view of a key. Has no field for the secret, and never will —
 * the same deliberate omission as {@code WhatsAppConnectionResponse} and
 * the access token. {@code keyId} is the identifying half and is safe to
 * show, so an operator can match a row here to a caller in the logs.
 */
public record ApiKeyResponse(
        UUID id,
        String keyId,
        String name,
        int rateLimit,
        boolean active,
        Instant lastUsedAt,
        Instant createdAt,
        Instant revokedAt
) {

    public static ApiKeyResponse from(ApiKey apiKey) {
        return new ApiKeyResponse(apiKey.getId(), apiKey.getKeyId(), apiKey.getName(), apiKey.getRateLimit(),
                apiKey.isActive(), apiKey.getLastUsedAt(), apiKey.getCreatedAt(), apiKey.getRevokedAt());
    }
}
