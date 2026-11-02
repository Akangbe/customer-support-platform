package com.supportplatform.client.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record InviteClientUserRequest(@NotBlank @Email String email, @NotBlank String name) {
}
