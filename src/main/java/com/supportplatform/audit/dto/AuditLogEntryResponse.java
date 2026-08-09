package com.supportplatform.audit.dto;

import com.supportplatform.audit.AuditAction;
import com.supportplatform.audit.AuditLogEntry;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntryResponse(
        UUID id,
        UUID actorUserId,
        AuditAction action,
        String targetType,
        UUID targetId,
        String detail,
        Instant occurredAt
) {
    public static AuditLogEntryResponse from(AuditLogEntry entry) {
        return new AuditLogEntryResponse(
                entry.getId(),
                entry.getActorUserId(),
                entry.getAction(),
                entry.getTargetType(),
                entry.getTargetId(),
                entry.getDetail(),
                entry.getOccurredAt()
        );
    }
}
