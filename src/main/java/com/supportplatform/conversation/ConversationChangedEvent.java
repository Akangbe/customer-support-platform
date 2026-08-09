package com.supportplatform.conversation;

import java.util.UUID;

/**
 * Raised whenever a conversation's persisted state changes (realtime-domain.md
 * §4). Identifiers only — the notification module re-fetches current state
 * through {@link ConversationService#getWithinTenant} after commit, rather
 * than broadcasting a snapshot that could be stale or rolled back.
 */
public record ConversationChangedEvent(UUID tenantId, UUID conversationId) {
}
