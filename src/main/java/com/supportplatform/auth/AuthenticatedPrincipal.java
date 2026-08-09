package com.supportplatform.auth;

import com.supportplatform.user.User;
import com.supportplatform.user.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static com.supportplatform.user.UserStatus.ACTIVE;

/**
 * The security principal for an authenticated request. {@code tenantId} is
 * the only legitimate source of tenant identity for a logged-in user
 * (Architecture Principles, Rule 3) — every tenant-scoped query must derive
 * it from here, never from client input.
 */
public class AuthenticatedPrincipal implements UserDetails {

    private final UUID userId;
    private final UUID tenantId;
    private final String email;
    private final String name;
    private final String passwordHash;
    private final UserRole role;
    private final boolean enabled;

    public AuthenticatedPrincipal(User user) {
        this.userId = user.getId();
        this.tenantId = user.getTenantId();
        this.email = user.getEmail();
        this.name = user.getName();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.enabled = user.getStatus() == ACTIVE;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
