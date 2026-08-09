package com.supportplatform.message.dto;

import com.supportplatform.message.Message;
import com.supportplatform.message.MessageDirection;
import com.supportplatform.message.MessageStatus;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        MessageDirection direction,
        MessageStatus status,
        String body,
        UUID senderUserId,
        String failureReason,
        Instant createdAt
) {
    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getDirection(),
                message.getStatus(),
                message.getBody(),
                message.getSenderUserId(),
                message.getFailureReason(),
                message.getCreatedAt()
        );
    }
}
