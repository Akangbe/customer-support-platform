package com.supportplatform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Reads and status transitions for {@code notification_log} — the delivery
 * half of the notification API.
 *
 * <p>Notification sends are recorded here rather than in {@code message},
 * so the status webhooks that already drive {@code Message}
 * (whatsapp-domain.md §7) have to reach these rows too. Without this,
 * every {@code statuses} event for a notification would fall through
 * {@code MessageService}'s "unrecognized wa_message_id" branch and be
 * dropped, and a notification would be stuck at SENT forever.
 */
@Service
public class NotificationLogService {

    private static final Logger log = LoggerFactory.getLogger(NotificationLogService.class);
    /** Only for a failed status that carried no {@code errors} block — the caller normally supplies Meta's own text. */
    private static final String UNSPECIFIED_FAILURE = "WhatsApp reported delivery failure";

    private final NotificationLogRepository notificationLogRepository;

    public NotificationLogService(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = notificationLogRepository;
    }

    /**
     * Applies a Meta status event to the notification it refers to.
     * Idempotent on {@code (tenantId, metaMessageId)} and monotonic (Rule 7)
     * — the entity's own guards refuse to walk a status backwards.
     *
     * @param failureReason Meta's own reason for a {@code failed} status,
     *                      already flattened by the caller (which is the only
     *                      place the raw webhook shape is known, Rule 4);
     *                      {@code null} for every other status
     * @return {@code true} if this id belonged to a notification, so the
     *         caller can tell "handled here" from "not ours"
     */
    @Transactional
    public boolean applyDeliveryStatus(UUID tenantId, String metaMessageId, String metaStatus, String failureReason) {
        return notificationLogRepository.findByTenantIdAndMetaMessageId(tenantId, metaMessageId)
                .map(notification -> {
                    switch (metaStatus) {
                        case "delivered" -> notification.markDelivered();
                        case "read" -> notification.markRead();
                        case "failed" -> notification.markDeliveryFailed(
                                failureReason == null || failureReason.isBlank() ? UNSPECIFIED_FAILURE : failureReason);
                        case "sent" -> { /* already SENT when the send returned; nothing to do */ }
                        default -> log.warn("Unrecognized WhatsApp status '{}' for notification {}", metaStatus, metaMessageId);
                    }
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public NotificationLog getWithinTenant(UUID tenantId, UUID notificationId) {
        return notificationLogRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Notification not found"));
    }

    /** Lookup by Meta's own id — what a caller has if they only kept what we returned as {@code metaMessageId}. */
    @Transactional(readOnly = true)
    public NotificationLog getByMetaMessageId(UUID tenantId, String metaMessageId) {
        return notificationLogRepository.findByTenantIdAndMetaMessageId(tenantId, metaMessageId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Notification not found"));
    }
}
