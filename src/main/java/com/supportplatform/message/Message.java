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
import java.util.List;
import java.util.UUID;

/**
 * A single inbound or outbound communication within a conversation
 * (message-domain.md). Status is outbound-only — inbound messages are
 * already a received fact and have nothing left to transition through.
 * The mark* transitions (whatsapp-domain.md §6-7) are called only by the
 * outbound sender and the status-webhook processor — both single,
 * internal callers — so they stay defensive-no-op rather than throwing
 * on an out-of-order call, since a redelivered status webhook is a normal
 * occurrence (Rule 7), not a bug.
 */
@Entity
@Table(name = "message")
@EntityListeners(AuditingEntityListener.class)
public class Message {
    /** ASCII record-separator (0x1E) - not a character any real template parameter would contain. */
    private static final String TEMPLATE_PARAM_SEPARATOR = String.valueOf((char) 0x1E);

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

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "template_language_code")
    private String templateLanguageCode;

    @Column(name = "template_params", columnDefinition = "TEXT")
    private String templateParamsRaw;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
    }

    private Message(UUID tenantId, UUID conversationId, MessageDirection direction, MessageStatus status,
                     String body, UUID senderUserId, String waMessageId,
                     String templateName, String templateLanguageCode, List<String> templateParams) {
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.direction = direction;
        this.status = status;
        this.body = body;
        this.senderUserId = senderUserId;
        this.waMessageId = waMessageId;
        this.templateName = templateName;
        this.templateLanguageCode = templateLanguageCode;
        this.templateParamsRaw = joinParams(templateParams);
    }

    /** Persisted PENDING — the outbound sender (whatsapp-domain.md §6) is what actually reaches WhatsApp and advances status from here. */
    public static Message outbound(UUID tenantId, UUID conversationId, UUID senderUserId, String body) {
        return new Message(tenantId, conversationId, MessageDirection.OUTBOUND, MessageStatus.PENDING, body, senderUserId, null, null, null, null);
    }

    /** Same as {@link #outbound}, but sent via an approved template — the only legal send outside the 24h window (message-domain.md §3, whatsapp-domain.md §8). */
    public static Message outboundTemplate(UUID tenantId, UUID conversationId, UUID senderUserId, String body,
                                            String templateName, String templateLanguageCode, List<String> templateParams) {
        return new Message(tenantId, conversationId, MessageDirection.OUTBOUND, MessageStatus.PENDING, body, senderUserId, null,
                templateName, templateLanguageCode, templateParams);
    }

    /** No status — an inbound message is already a received fact. */
    public static Message inbound(UUID tenantId, UUID conversationId, String waMessageId, String body) {
        return new Message(tenantId, conversationId, MessageDirection.INBOUND, null, body, null, waMessageId, null, null, null);
    }

    public void markSent(String waMessageId) {
        this.waMessageId = waMessageId;
        this.status = MessageStatus.SENT;
        this.failureReason = null;
        this.nextAttemptAt = null;
    }

    /** No-op unless currently SENT — a redelivered or out-of-order webhook must never downgrade READ back to DELIVERED. */
    public void markDelivered() {
        if (status == MessageStatus.SENT) {
            this.status = MessageStatus.DELIVERED;
        }
    }

    public void markRead() {
        if (status == MessageStatus.SENT || status == MessageStatus.DELIVERED) {
            this.status = MessageStatus.READ;
        }
    }

    /** Terminal. Only reachable from PENDING (never sent) or SENT (rejected downstream) — DELIVERED/READ already succeeded. */
    public void markFailed(String reason) {
        if (status == MessageStatus.PENDING || status == MessageStatus.SENT) {
            this.status = MessageStatus.FAILED;
            this.failureReason = reason;
        }
    }

    /** A transient send failure: stays PENDING, scheduled for another attempt (whatsapp-domain.md §6's 4^attempt backoff). */
    public void recordSendAttemptFailure(String reason, Instant nextAttemptAt) {
        this.attemptCount++;
        this.failureReason = reason;
        this.nextAttemptAt = nextAttemptAt;
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

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getTemplateLanguageCode() {
        return templateLanguageCode;
    }

    public List<String> getTemplateParams() {
        return splitParams(templateParamsRaw);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String joinParams(List<String> params) {
        return (params == null || params.isEmpty()) ? null : String.join(TEMPLATE_PARAM_SEPARATOR, params);
    }

    private static List<String> splitParams(String stored) {
        return (stored == null || stored.isEmpty()) ? List.of() : List.of(stored.split(TEMPLATE_PARAM_SEPARATOR, -1));
    }
}
