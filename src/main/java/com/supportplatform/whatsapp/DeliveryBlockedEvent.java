package com.supportplatform.whatsapp;

import java.util.UUID;

/**
 * Raised when Meta refuses a delivery for a reason a person has to fix
 * ({@link MetaBlockingError}). Published from the webhook handler and
 * consumed after commit, so an alert never describes a failure the
 * database rolled back.
 *
 * <p>Identifiers plus the already-flattened reason: the consumer must not
 * need to re-parse Meta's payload, which only {@link WebhookEventHandler}
 * is allowed to understand (Rule 4).
 *
 * @param templateName the template the blocked send used, for a
 *                     {@code TEMPLATE}-scoped error; may be {@code null}
 *                     when the failure was a conversation message rather
 *                     than a notification
 * @param apiKeyId     the key whose send a {@code TEMPLATE}-scoped error came
 *                     from, so only that client is told about its template;
 *                     {@code null} for a block on the whole number
 */
public record DeliveryBlockedEvent(UUID tenantId, MetaBlockingError error, String templateName, UUID apiKeyId,
                                   String failureReason) {
}
