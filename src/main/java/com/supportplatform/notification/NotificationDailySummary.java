package com.supportplatform.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One API key's day of sends, summarized and mailed (V18).
 *
 * <p>Both the guard that stops the summary going out twice and the record
 * of what the integrator was told. The figures are frozen at the moment of
 * summarizing on purpose: late delivery receipts keep moving the live
 * counts, and a disputed bill is answered with what was mailed, not with a
 * recount that no longer matches it.
 */
@Entity
@Table(name = "notification_daily_summary")
public class NotificationDailySummary {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    /** The UTC day summarized — not when the mail went out. */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private int sends;

    @Column(nullable = false)
    private int delivered;

    @Column(nullable = false)
    private int failed;

    @Column(name = "price_per_delivered", nullable = false)
    private BigDecimal pricePerDelivered;

    @Column(nullable = false)
    private BigDecimal cost;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "summarized_at", nullable = false)
    private Instant summarizedAt;

    protected NotificationDailySummary() {
    }

    public NotificationDailySummary(DailySpendSummaryEvent event) {
        this.tenantId = event.tenantId();
        this.apiKeyId = event.apiKeyId();
        this.periodStart = event.periodStart();
        this.sends = (int) Math.min(event.sends(), Integer.MAX_VALUE);
        this.delivered = (int) Math.min(event.delivered(), Integer.MAX_VALUE);
        this.failed = (int) Math.min(event.failed(), Integer.MAX_VALUE);
        this.pricePerDelivered = event.pricePerDelivered();
        this.cost = event.cost();
        this.currency = event.currency();
        this.summarizedAt = Instant.now();
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

    public int getSends() {
        return sends;
    }

    public int getDelivered() {
        return delivered;
    }

    public int getFailed() {
        return failed;
    }

    public BigDecimal getPricePerDelivered() {
        return pricePerDelivered;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getSummarizedAt() {
        return summarizedAt;
    }
}
