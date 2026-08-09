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
        UUID attachmentId,
        String failureReason,
        Instant createdAt
) {
    public static MessageResponse from(Message message, UUID attachmentId) {
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getDirection(),
                message.getStatus(),
                message.getBody(),
                message.getSenderUserId(),
                attachmentId,
                message.getFailureReason(),
                message.getCreatedAt()
        );
    }
}
