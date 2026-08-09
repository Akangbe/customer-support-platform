package com.supportplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterTenantRequest(
        @NotBlank String tenantName,
        @NotBlank String ownerName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 100) String password
) {
}
