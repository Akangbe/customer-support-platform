package com.supportplatform.user.dto;

import com.supportplatform.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InviteUserRequest(
        @NotBlank @Email String email,
        @NotBlank String name,
        @NotNull UserRole role
) {
}
