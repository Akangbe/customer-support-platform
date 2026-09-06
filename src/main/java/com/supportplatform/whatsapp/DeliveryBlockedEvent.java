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
 */
public record DeliveryBlockedEvent(UUID tenantId, MetaBlockingError error, String templateName, String failureReason) {
}
