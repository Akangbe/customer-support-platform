package com.supportplatform.notification.dto;

import com.supportplatform.notification.NotificationLog;
import com.supportplatform.notification.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * What a tenant's backend gets when it asks whether a notification landed.
 *
 * <p>Carries {@code failureReason} — unlike the send-time error path, which
 * deliberately withholds Meta's text. By this point the failure is a fact
 * about the tenant's own message ("no matching user for the phone number",
 * "message undeliverable"), which is exactly what they need to act on, and
 * it names nothing internal: no token, no phone_number_id, no Graph URL.
 */
public record NotificationStatusResponse(
        UUID notificationId,
        String metaMessageId,
        NotificationStatus status,
        String recipient,
        String templateName,
        String languageCode,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    public static NotificationStatusResponse from(NotificationLog log) {
        return new NotificationStatusResponse(log.getId(), log.getMetaMessageId(), log.getStatus(),
                log.getRecipient(), log.getTemplateName(), log.getLanguageCode(), log.getFailureReason(),
                log.getCreatedAt(), log.getUpdatedAt());
    }
}
