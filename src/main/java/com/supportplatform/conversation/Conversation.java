package com.supportplatform.conversation;

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
 * The central aggregate (domain-model.md): owns its status, priority, and
 * current assignment. Status transitions are a state machine, not a
 * free-form field — see conversation-domain.md §3 for the diagram this
 * code encodes.
 */
@Entity
@Table(name = "conversation")
@EntityListeners(AuditingEntityListener.class)
public class Conversation {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationPriority priority;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_inbound_at")
    private Instant lastInboundAt;

    @Column(name = "last_outbound_at")
    private Instant lastOutboundAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Conversation() {
    }

    public Conversation(UUID tenantId, UUID customerId) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.status = ConversationStatus.OPEN;
        this.priority = ConversationPriority.NORMAL;
    }

    /** Valid from OPEN (claim) or ASSIGNED (reassign) — the service layer decides who's allowed to call this. */
    public void assign(UUID agentId) {
        if (status == ConversationStatus.CLOSED) {
            throw new InvalidConversationStateException("Cannot assign a closed conversation; reopen it first");
        }
        this.assignedAgentId = agentId;
        this.status = ConversationStatus.ASSIGNED;
    }

    public void unassign() {
        if (status != ConversationStatus.ASSIGNED) {
            throw new InvalidConversationStateException("Conversation is not currently assigned");
        }
        this.assignedAgentId = null;
        this.status = ConversationStatus.OPEN;
    }

    public void close(Instant when) {
        if (status == ConversationStatus.CLOSED) {
            throw new InvalidConversationStateException("Conversation is already closed");
        }
        this.status = ConversationStatus.CLOSED;
        this.closedAt = when;
    }

    /** Per ADR-013: always lands back in OPEN, unassigned — the previous assignment is not silently restored. */
    public void reopen() {
        if (status != ConversationStatus.CLOSED) {
            throw new InvalidConversationStateException("Only a closed conversation can be reopened");
        }
        this.status = ConversationStatus.OPEN;
        this.closedAt = null;
        this.assignedAgentId = null;
    }

    public void changePriority(ConversationPriority priority) {
        this.priority = priority;
    }

    public void recordInboundAt(Instant when) {
        this.lastInboundAt = when;
    }

    public void recordOutboundAt(Instant when) {
        this.lastOutboundAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public ConversationPriority getPriority() {
        return priority;
    }

    public UUID getAssignedAgentId() {
        return assignedAgentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastInboundAt() {
        return lastInboundAt;
    }

    public Instant getLastOutboundAt() {
        return lastOutboundAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
