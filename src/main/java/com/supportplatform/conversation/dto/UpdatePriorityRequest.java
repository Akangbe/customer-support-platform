package com.supportplatform.conversation.dto;

import com.supportplatform.conversation.ConversationPriority;
import jakarta.validation.constraints.NotNull;

public record UpdatePriorityRequest(
        @NotNull ConversationPriority priority
) {
}
