package com.supportplatform.user;

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

@Entity
@Table(name = "app_user")
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "invite_token")
    private String inviteToken;

    @Column(name = "invite_token_expires_at")
    private Instant inviteTokenExpiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
    }

    private User(UUID tenantId, String email, String name, UserRole role, UserStatus status) {
        this.tenantId = tenantId;
        this.email = email;
        this.name = name;
        this.role = role;
        this.status = status;
    }

    /** The tenant's first user, created directly active by self-registration. */
    public static User createOwner(UUID tenantId, String email, String name, String passwordHash) {
        User user = new User(tenantId, email, name, UserRole.OWNER, UserStatus.ACTIVE);
        user.passwordHash = passwordHash;
        return user;
    }

    /** An invited user, pending until they accept the invite and set a password. */
    public static User createInvited(UUID tenantId, String email, String name, UserRole role,
                                      String inviteToken, Instant inviteTokenExpiresAt) {
        User user = new User(tenantId, email, name, role, UserStatus.PENDING);
        user.inviteToken = inviteToken;
        user.inviteTokenExpiresAt = inviteTokenExpiresAt;
        return user;
    }

    public void activate(String passwordHash) {
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        this.inviteToken = null;
        this.inviteTokenExpiresAt = null;
    }

    public void changeRole(UserRole newRole) {
        this.role = newRole;
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }

    public void enable() {
        this.status = UserStatus.ACTIVE;
    }

    public void recordLogin(Instant when) {
        this.lastLoginAt = when;
    }

    public boolean isInviteTokenValid(String token, Instant now) {
        return status == UserStatus.PENDING
                && inviteToken != null
                && inviteToken.equals(token)
                && inviteTokenExpiresAt != null
                && inviteTokenExpiresAt.isAfter(now);
    }

    public String getInviteToken() {
        return inviteToken;
    }

    public Instant getInviteTokenExpiresAt() {
        return inviteTokenExpiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
