package com.supportplatform.notification;

/**
 * The delivery lifecycle of one notification, mirroring the CHECK
 * constraint on {@code notification_log.status} and — deliberately —
 * {@code MessageStatus}'s vocabulary, minus {@code PENDING}: a
 * notification send is synchronous, so it is already SENT or FAILED by
 * the time the caller gets a response.
 *
 * <p>SENT → DELIVERED → READ is driven by Meta's status webhooks, the same
 * ones that already drive {@code Message} (whatsapp-domain.md §7).
 */
public enum NotificationStatus {
    SENT,
    DELIVERED,
    READ,
    FAILED
}
