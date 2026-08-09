package com.supportplatform.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's WhatsApp Business Account credentials — a tenant-level
 * singleton (domain-model.md, whatsapp-domain.md §2). {@code accessToken}
 * is encrypted at rest via {@link CredentialConverter}; it is never
 * exposed outside this module (see {@code WhatsAppConnectionResponse},
 * which deliberately has no field for it).
 */
@Entity
@Table(name = "whatsapp_connection")
@EntityListeners(AuditingEntityListener.class)
public class WhatsAppConnection {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "phone_number_id", nullable = false, unique = true)
    private String phoneNumberId;

    @Column(name = "waba_id", nullable = false)
    private String wabaId;

    @Convert(converter = CredentialConverter.class)
    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WhatsAppConnection() {
    }

    public WhatsAppConnection(UUID tenantId, String phoneNumberId, String wabaId, String accessToken) {
        this.tenantId = tenantId;
        this.phoneNumberId = phoneNumberId;
        this.wabaId = wabaId;
        this.accessToken = accessToken;
    }

    /** Connect is an upsert (whatsapp-domain.md §2) — token rotation is a real operational need even for one owned WABA. */
    public void reconfigure(String phoneNumberId, String wabaId, String accessToken) {
        this.phoneNumberId = phoneNumberId;
        this.wabaId = wabaId;
        this.accessToken = accessToken;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public String getWabaId() {
        return wabaId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
