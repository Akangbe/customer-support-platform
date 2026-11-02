package com.supportplatform.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One business sending through the platform (V20). Not a {@code Customer},
 * which is a WhatsApp contact in a tenant's inbox. Several clients can share
 * a tenant when they share its WhatsApp number, so this, not the tenant, is
 * the boundary for anything a client can read.
 */
@Entity
@Table(name = "client_account")
@EntityListeners(AuditingEntityListener.class)
public class ClientAccount {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClientAccount() {
    }

    public ClientAccount(UUID tenantId, String name) {
        this.tenantId = tenantId;
        this.name = name;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
