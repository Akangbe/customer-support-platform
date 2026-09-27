package com.supportplatform.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A record that one daily volume threshold has already been alerted on, so
 * it is not alerted on again.
 *
 * <p>Durable rather than in-memory on purpose. The service spins down when
 * idle and cold starts several times a day; an in-process flag would be
 * lost on every wake and the same threshold would be re-announced each
 * time. An alert that arrives six times a day is one nobody reads.
 */
@Entity
@Table(name = "notification_usage_alert")
public class NotificationUsageAlert {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The key whose sends were counted (V19). Null only on rows from before the alert was per key. */
    @Column(name = "api_key_id")
    private UUID apiKeyId;

    /** The UTC day whose sends were counted — not when the mail went out. */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private int threshold;

    @Column(name = "sends_at_alert", nullable = false)
    private int sendsAtAlert;

    @Column(name = "alerted_at", nullable = false)
    private Instant alertedAt;

    protected NotificationUsageAlert() {
    }

    public NotificationUsageAlert(UUID tenantId, UUID apiKeyId, LocalDate periodStart, int threshold, int sendsAtAlert) {
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.periodStart = periodStart;
        this.threshold = threshold;
        this.sendsAtAlert = sendsAtAlert;
        this.alertedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public int getThreshold() {
        return threshold;
    }

    public int getSendsAtAlert() {
        return sendsAtAlert;
    }

    public Instant getAlertedAt() {
        return alertedAt;
    }
}
