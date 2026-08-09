package com.supportplatform.conversation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignConversationRequest(
        @NotNull UUID agentId
) {
}
