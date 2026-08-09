package com.supportplatform.tenant;

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

@Entity
@Table(name = "tenant")
@EntityListeners(AuditingEntityListener.class)
public class Tenant {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(String name, String slug) {
        this.name = name;
        this.slug = slug;
        this.status = TenantStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
