package com.supportplatform.user.dto;

import com.supportplatform.user.UserRole;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(
        @NotNull UserRole role
) {
}
