package com.supportplatform.storage.dto;

import com.supportplatform.storage.Attachment;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        String contentType,
        String fileName,
        long sizeBytes,
        Instant createdAt
) {
    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getContentType(),
                attachment.getFileName(),
                attachment.getSizeBytes(),
                attachment.getCreatedAt()
        );
    }
}
