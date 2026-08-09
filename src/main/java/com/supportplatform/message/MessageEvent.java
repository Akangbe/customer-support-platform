package com.supportplatform.message;

import java.util.UUID;

/**
 * Raised on a new message or a delivery-status transition
 * (realtime-domain.md §4). Identifiers only — the notification module
 * re-fetches current state through {@link MessageService#getWithinTenant}
 * after commit, rather than broadcasting a snapshot that could be stale
 * or rolled back.
 */
public record MessageEvent(UUID tenantId, UUID conversationId, UUID messageId) {
}
