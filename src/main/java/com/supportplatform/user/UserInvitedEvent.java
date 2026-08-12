package com.supportplatform.user;

import java.util.UUID;

/**
 * Raised after an invite is created (identity-and-access.md §5).
 * Identifiers only, same convention as {@code ConversationChangedEvent} —
 * {@code com.supportplatform.email.InviteEmailListener} re-fetches the user
 * (and its token/expiry) after commit rather than trusting a snapshot that
 * could be stale or belong to a rolled-back transaction.
 */
public record UserInvitedEvent(UUID tenantId, UUID userId) {
}
