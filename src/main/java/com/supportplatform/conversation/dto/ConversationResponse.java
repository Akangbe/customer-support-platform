package com.supportplatform.conversation.dto;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationPriority;
import com.supportplatform.conversation.ConversationStatus;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID customerId,
        ConversationStatus status,
        ConversationPriority priority,
        UUID assignedAgentId,
        Instant createdAt,
        Instant lastInboundAt,
        Instant lastOutboundAt,
        UUID lastResponderAgentId,
        Instant closedAt
) {
    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getCustomerId(),
                conversation.getStatus(),
                conversation.getPriority(),
                conversation.getAssignedAgentId(),
                conversation.getCreatedAt(),
                conversation.getLastInboundAt(),
                conversation.getLastOutboundAt(),
                conversation.getLastResponderAgentId(),
                conversation.getClosedAt()
        );
    }
}
