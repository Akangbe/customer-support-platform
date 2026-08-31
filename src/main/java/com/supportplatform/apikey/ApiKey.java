package com.supportplatform.apikey;

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
 * A tenant's machine-to-machine credential for the notification API
 * (Rule 3: the tenant a request acts as is derived from this row, never
 * from the request body). Only {@code secretHash} is stored — the secret
 * half is shown once at creation and is unrecoverable afterwards, the
 * same posture as {@code app_user.password_hash}.
 */
@Entity
@Table(name = "api_key")
@EntityListeners(AuditingEntityListener.class)
public class ApiKey {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The plaintext lookup handle — safe to store and log; it identifies, it doesn't authenticate. */
    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    @Column(name = "secret_hash", nullable = false)
    private String secretHash;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "rate_limit", nullable = false)
    private int rateLimit;

    /** The kill switch: flipped false on revoke, checked on every authenticated request. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
    }

    public ApiKey(UUID tenantId, String keyId, String secretHash, String name, int rateLimit) {
        this.tenantId = tenantId;
        this.keyId = keyId;
        this.secretHash = secretHash;
        this.name = name;
        this.rateLimit = rateLimit;
    }

    /**
     * The kill switch, thrown. Reversible by design ({@link #reactivate()}):
     * the operational case this exists for is "stop this tenant sending
     * right now, while we work out what is wrong", which is usually
     * followed by turning them back on rather than by issuing a new
     * credential.
     */
    public void deactivate() {
        this.active = false;
        this.revokedAt = Instant.now();
    }

    /** Clears {@code revokedAt} too, so the column always answers "since when has this key been off". */
    public void reactivate() {
        this.active = true;
        this.revokedAt = null;
    }

    public void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public String getName() {
        return name;
    }

    public int getRateLimit() {
        return rateLimit;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
