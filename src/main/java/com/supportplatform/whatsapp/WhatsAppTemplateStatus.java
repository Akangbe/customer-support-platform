package com.supportplatform.whatsapp;

/**
 * Meta's own template review states, mirrored (whatsapp-domain.md §8).
 * Only {@link #APPROVED} is sendable — the rest exist so an operator can
 * record why a template is currently unusable rather than deleting the row
 * and losing the history.
 */
public enum WhatsAppTemplateStatus {
    APPROVED,
    PENDING,
    REJECTED,
    /** Meta throttles a template whose recipients report it; sends resume only when Meta lifts it. */
    PAUSED,
    DISABLED;

    public boolean isSendable() {
        return this == APPROVED;
    }
}
