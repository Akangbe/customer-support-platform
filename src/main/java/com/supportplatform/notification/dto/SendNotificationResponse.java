package com.supportplatform.notification.dto;

import com.supportplatform.notification.NotificationLog;
import com.supportplatform.notification.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * What the tenant's backend gets back. {@code notificationId} is the
 * {@code notification_log} row, which is the id to quote when asking us
 * about a specific send.
 */
public record SendNotificationResponse(
        UUID notificationId,
        NotificationStatus status,
        String metaMessageId,
        Instant createdAt
) {

    public static SendNotificationResponse from(NotificationLog log) {
        return new SendNotificationResponse(log.getId(), log.getStatus(), log.getMetaMessageId(), log.getCreatedAt());
    }
}
