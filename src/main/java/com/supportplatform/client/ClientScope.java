package com.supportplatform.client;

import java.util.UUID;

/**
 * What a signed-in client may read. Only {@link ClientScopeResolver} creates
 * one, from the session, so a client-facing query never takes an account id
 * from a request.
 */
public record ClientScope(UUID userId, UUID tenantId, UUID clientAccountId) {
}
