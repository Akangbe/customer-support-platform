package com.supportplatform.apikey;

import java.util.UUID;

/**
 * The security principal for an API-key-authenticated request — the
 * machine counterpart to {@code AuthenticatedPrincipal}. As with that
 * class, {@code tenantId} here is the only legitimate source of tenant
 * identity for the request (Rule 3): it comes off the key row, never off
 * the request body. Carries no secret material.
 */
public record ApiKeyPrincipal(UUID apiKeyId, UUID tenantId, String keyId, String name, int rateLimit) {
}
