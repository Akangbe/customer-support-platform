package com.supportplatform.audit;

import java.util.UUID;

/**
 * A generic, logic-free event a producer module publishes via Spring's
 * {@code ApplicationEventPublisher} (audit-domain.md §3) — one shape
 * for every audited action, unlike {@code ConversationChangedEvent}/
 * {@code MessageEvent} (realtime-domain.md §4), which are
 * identifiers-only. This one carries exactly what a compliance record
 * needs and a later re-fetch could never recover: who acted, and what
 * specifically happened, captured at the moment of mutation.
 */
public record AuditEvent(UUID tenantId, UUID actorUserId, AuditAction action, String targetType, UUID targetId, String detail) {
}
