package com.supportplatform.notification;

import java.util.UUID;

/**
 * Meta rejected the send. Carries the {@code notification_log} id so the
 * caller can quote it, but not Meta's own error text — that goes to our
 * logs only, since it can name internal identifiers (phone_number_id, the
 * Graph URL) the tenant has no business seeing.
 */
public class NotificationDeliveryException extends RuntimeException {

    private final UUID notificationId;

    public NotificationDeliveryException(UUID notificationId) {
        super("WhatsApp rejected the notification");
        this.notificationId = notificationId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }
}
