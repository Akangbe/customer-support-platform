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
        return new AuthResponse(user.getId(), user.getTenantId(), user.getEmail(), user.getName(), user.getRole());
    }

    public static AuthResponse from(AuthenticatedPrincipal principal) {
        return new AuthResponse(principal.getUserId(), principal.getTenantId(), principal.getUsername(),
                principal.getName(), principal.getRole());
    }
}
