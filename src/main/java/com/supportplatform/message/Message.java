package com.supportplatform.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A single inbound or outbound communication within a conversation
 * (message-domain.md). Status is outbound-only — inbound messages are
 * already a received fact and have nothing left to transition through.
 */
@Entity
@Table(name = "message")
@EntityListeners(AuditingEntityListener.class)
public class Message {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MessageStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "sender_user_id")
    private UUID senderUserId;

    @Column(name = "wa_message_id")
    private String waMessageId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
    }

    private Message(UUID tenantId, UUID conversationId, MessageDirection direction, MessageStatus status,
                     String body, UUID senderUserId, String waMessageId) {
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.direction = direction;
        this.status = status;
        this.body = body;
        this.senderUserId = senderUserId;
        this.waMessageId = waMessageId;
    }

    /** Persisted PENDING — the outbox (Phase 6) is what actually reaches WhatsApp and advances status from here. */
    public static Message outbound(UUID tenantId, UUID conversationId, UUID senderUserId, String body) {
        return new Message(tenantId, conversationId, MessageDirection.OUTBOUND, MessageStatus.PENDING, body, senderUserId, null);
    }

    /** No status — an inbound message is already a received fact. */
    public static Message inbound(UUID tenantId, UUID conversationId, String waMessageId, String body) {
        return new Message(tenantId, conversationId, MessageDirection.INBOUND, null, body, null, waMessageId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public UUID getSenderUserId() {
        return senderUserId;
    }

    public String getWaMessageId() {
        return waMessageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
