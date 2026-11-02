package com.supportplatform.auth.dto;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRole;

import java.util.UUID;

public record AuthResponse(
        UUID userId,
        UUID tenantId,
        String email,
        String name,
        UserRole role
) {
    public static AuthResponse from(User user) {
        return new AuthResponse(user.getId(), visibleTenantId(user.getRole(), user.getTenantId()), user.getEmail(),
                user.getName(), user.getRole());
    }

    public static AuthResponse from(AuthenticatedPrincipal principal) {
        return new AuthResponse(principal.getUserId(), visibleTenantId(principal.getRole(), principal.getTenantId()),
                principal.getUsername(), principal.getName(), principal.getRole());
    }

    /** A client user's tenant may be the operator's own, which is not theirs to know. */
    private static UUID visibleTenantId(UserRole role, UUID tenantId) {
        return role == UserRole.CLIENT ? null : tenantId;
    }
}
