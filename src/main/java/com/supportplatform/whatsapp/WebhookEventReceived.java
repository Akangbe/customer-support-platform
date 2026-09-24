package com.supportplatform.whatsapp;

/**
 * A verified webhook delivery has been stored and is waiting to be
 * processed. Carries nothing: the row is the work, and the processor reads
 * it back from the table like any other pending event.
 */
public record WebhookEventReceived() {
}
