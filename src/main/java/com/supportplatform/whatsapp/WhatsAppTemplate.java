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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One template a tenant has had approved on their own WABA — the allowlist
 * the notification send path checks against (whatsapp-domain.md §8).
 *
 * <p>This is a guardrail, not a source of truth: Meta is the authority on
 * whether a template is really approved, and it can pause or disable one
 * without telling us. Its job is to turn the common, predictable failure
 * ("that template isn't yours" / "it isn't approved yet") into a clear
 * 4xx at our edge instead of a relayed Graph API error code the caller has
 * to decode.
 */
@Entity
@Table(name = "whatsapp_template")
@EntityListeners(AuditingEntityListener.class)
public class WhatsAppTemplate {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WhatsAppTemplateStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WhatsAppTemplate() {
    }

    public WhatsAppTemplate(UUID tenantId, String name, WhatsAppTemplateStatus status) {
        this.tenantId = tenantId;
        this.name = name;
        this.status = status;
    }

    /** Registering a name a tenant already has is a status update, not a second row (see the unique index in V13). */
    public void updateStatus(WhatsAppTemplateStatus status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public WhatsAppTemplateStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
