package com.supportplatform.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * The audit record for one API-driven notification send, written whether
 * the send succeeded or failed.
 *
 * <p>Deliberately not a {@code message} row: {@code message} models turns
 * in a support conversation and requires a {@code conversation_id} and a
 * {@code customer}, neither of which a fire-and-forget template
 * notification from a tenant's own backend has. Folding these in would
 * have meant auto-creating a customer and conversation per notification
 * and polluting agents' inboxes — a change to the inbox's meaning, not an
 * addition to it (Rule 1).
 *
 * <p>Records no message content and no credential: the template name and
 * the outcome, not the rendered body.
 */
@Entity
@Table(name = "notification_log")
@EntityListeners(AuditingEntityListener.class)
public class NotificationLog {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Which key sent it — nullable so a revoked-and-deleted key never takes its history with it. */
    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(nullable = false, length = 20)
    private String recipient;

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @Column(name = "language_code", nullable = false, length = 20)
    private String languageCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "meta_message_id")
    private String metaMessageId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the status last moved — i.e. when Meta last told us something about this send. */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationLog() {
    }

    private NotificationLog(UUID tenantId, UUID apiKeyId, String recipient, String templateName, String languageCode,
                             NotificationStatus status, String metaMessageId, String failureReason) {
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.recipient = recipient;
        this.templateName = templateName;
        this.languageCode = languageCode;
        this.status = status;
        this.metaMessageId = metaMessageId;
        this.failureReason = failureReason;
    }

    public static NotificationLog sent(UUID tenantId, UUID apiKeyId, String recipient, String templateName,
                                         String languageCode, String metaMessageId) {
        return new NotificationLog(tenantId, apiKeyId, recipient, templateName, languageCode,
                NotificationStatus.SENT, metaMessageId, null);
    }

    public static NotificationLog failed(UUID tenantId, UUID apiKeyId, String recipient, String templateName,
                                           String languageCode, String failureReason) {
        return new NotificationLog(tenantId, apiKeyId, recipient, templateName, languageCode,
                NotificationStatus.FAILED, null, failureReason);
    }

    /**
     * Webhook-driven transitions. Guarded exactly as {@code Message}'s
     * equivalents are: Meta delivers status events at-least-once and out of
     * order (Rule 7), so a redelivered "delivered" must never drag a row
     * back down from READ.
     */
    public void markDelivered() {
        if (status == NotificationStatus.SENT) {
            this.status = NotificationStatus.DELIVERED;
        }
    }

    public void markRead() {
        if (status == NotificationStatus.SENT || status == NotificationStatus.DELIVERED) {
            this.status = NotificationStatus.READ;
        }
    }

    /** Meta rejected it after accepting it. Only reachable from SENT — DELIVERED/READ already succeeded. */
    public void markDeliveryFailed(String reason) {
        if (status == NotificationStatus.SENT) {
            this.status = NotificationStatus.FAILED;
            this.failureReason = reason;
        }
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

    public String getRecipient() {
        return recipient;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public String getMetaMessageId() {
        return metaMessageId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
