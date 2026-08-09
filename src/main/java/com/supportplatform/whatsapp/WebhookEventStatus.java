package com.supportplatform.whatsapp;

public enum WebhookEventStatus {
    PENDING,
    PROCESSED,
    /** Never guessed at — no whatsapp_connection mapped the event's phone_number_id (whatsapp-domain.md §4). */
    DROPPED,
    /** Terminal: exhausted retry attempts on a real processing error. */
    FAILED
}
