package com.supportplatform.user.dto;

import com.supportplatform.user.User;
import com.supportplatform.user.UserRole;
import com.supportplatform.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserSummaryResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        UserStatus status,
        Instant createdAt,
        Instant lastLoginAt
) {
    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getStatus(), user.getCreatedAt(), user.getLastLoginAt());
    }
}
