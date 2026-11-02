package com.supportplatform.client.dto;

import com.supportplatform.client.ClientAccount;

import java.time.Instant;
import java.util.UUID;

public record ClientAccountResponse(UUID id, String name, Instant createdAt) {

    public static ClientAccountResponse from(ClientAccount account) {
        return new ClientAccountResponse(account.getId(), account.getName(), account.getCreatedAt());
    }
}
