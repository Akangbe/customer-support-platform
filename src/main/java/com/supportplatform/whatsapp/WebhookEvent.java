package com.supportplatform.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * The inbound counterpart to ADR-012's outbox: a durable landing zone for
 * a signature-verified webhook delivery, processed asynchronously by a
 * poller (whatsapp-domain.md §4). Not itself a dedupe boundary — a
 * duplicate row processes fine independently, because
 * {@code Message}'s {@code (tenant_id, wa_message_id)} constraint is
 * where real idempotency lives (ADR-012).
 */
@Entity
@Table(name = "webhook_event")
@EntityListeners(AuditingEntityListener.class)
public class WebhookEvent {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookEventStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(columnDefinition = "TEXT")
    private String error;

    @CreatedDate
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookEvent() {
    }

    private WebhookEvent(String payload) {
        this.payload = payload;
        this.status = WebhookEventStatus.PENDING;
    }

    public static WebhookEvent received(String payload) {
        return new WebhookEvent(payload);
    }

    public void markProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = Instant.now();
    }

    public void drop(String reason) {
        this.status = WebhookEventStatus.DROPPED;
        this.error = reason;
        this.processedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = WebhookEventStatus.FAILED;
        this.error = reason;
        this.processedAt = Instant.now();
    }

    /** A transient processing failure: stays PENDING, scheduled for another attempt (same 4^attempt backoff as the outbound sender). */
    public void recordAttemptFailure(String reason, Instant nextAttemptAt) {
        this.attemptCount++;
        this.error = reason;
        this.nextAttemptAt = nextAttemptAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPayload() {
        return payload;
    }

    public WebhookEventStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getError() {
        return error;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
