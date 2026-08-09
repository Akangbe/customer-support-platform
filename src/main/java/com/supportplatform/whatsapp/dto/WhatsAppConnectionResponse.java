package com.supportplatform.whatsapp.dto;

import com.supportplatform.whatsapp.WhatsAppConnection;

import java.time.Instant;
import java.util.UUID;

/** Deliberately no access-token field — credentials are never echoed back (NFR-SEC, whatsapp-domain.md §2). */
public record WhatsAppConnectionResponse(
        UUID id,
        String phoneNumberId,
        String wabaId,
        Instant connectedAt,
        Instant updatedAt
) {
    public static WhatsAppConnectionResponse from(WhatsAppConnection connection) {
        return new WhatsAppConnectionResponse(
                connection.getId(),
                connection.getPhoneNumberId(),
                connection.getWabaId(),
                connection.getCreatedAt(),
                connection.getUpdatedAt()
        );
    }
}
