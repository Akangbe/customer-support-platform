package com.supportplatform.notification;

/**
 * The delivery lifecycle of one notification, mirroring the CHECK
 * constraint on {@code notification_log.status} and — deliberately —
 * {@code MessageStatus}'s vocabulary. PENDING exists only inside a
 * single request, between claiming the row and hearing back from Meta;
 * a caller never sees it unless that request died in between.
 *
 * <p>SENT → DELIVERED → READ is driven by Meta's status webhooks, the same
 * ones that already drive {@code Message} (whatsapp-domain.md §7).
 */
public enum NotificationStatus {
    /**
     * Claimed before the Graph API call (V15), so a concurrent retry has
     * something to collide with. A row resting here means the process died
     * between claiming it and hearing back from Meta: the message may or may
     * not have gone out. That ambiguity is deliberate and visible —
     * investigating a PENDING row is cheaper than billing a customer twice.
     */
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
