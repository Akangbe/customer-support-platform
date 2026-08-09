package com.supportplatform.message;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationService;
import com.supportplatform.conversation.ConversationStatus;
import com.supportplatform.conversation.InvalidConversationStateException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Message persistence and the 24-hour service-window rule
 * (message-domain.md). Tenant scoping flows through
 * {@link ConversationService#getWithinTenant}, the same 404-on-guess
 * protection every other tenant-owned resource uses.
 */
@Service
public class MessageService {

    private static final Duration SERVICE_WINDOW = Duration.ofHours(24);

    private final MessageRepository messageRepository;
    private final ConversationService conversationService;

    public MessageService(MessageRepository messageRepository, ConversationService conversationService) {
        this.messageRepository = messageRepository;
        this.conversationService = conversationService;
    }

    /**
     * An agent replying. Persists PENDING and stops there — reaching
     * WhatsApp is Phase 6's outbox, not this method's job.
     */
    @Transactional
    public Message sendOutbound(UUID tenantId, UUID conversationId, UUID senderUserId, String body) {
        Conversation conversation = conversationService.getWithinTenant(tenantId, conversationId);

        if (conversation.getStatus() == ConversationStatus.CLOSED) {
            throw new InvalidConversationStateException("Cannot send a message on a closed conversation; reopen it first");
        }

        Instant lastInboundAt = conversation.getLastInboundAt();
        if (lastInboundAt == null || lastInboundAt.isBefore(Instant.now().minus(SERVICE_WINDOW))) {
            throw new OutsideServiceWindowException(
                    "Outside the 24-hour customer-service window; a template message is required");
        }

        Message message = Message.outbound(tenantId, conversationId, senderUserId, body);
        conversation.recordOutboundAt(Instant.now());
        return messageRepository.save(message);
    }

    /**
     * Idempotent on {@code (tenantId, waMessageId)} (ADR-012) — safe to
     * call more than once for the same Meta message id. Not reachable
     * over HTTP yet; Phase 6's webhook is the eventual caller.
     */
    @Transactional
    public Message recordInbound(UUID tenantId, UUID conversationId, String waMessageId, String body) {
        return messageRepository.findByTenantIdAndWaMessageId(tenantId, waMessageId)
                .orElseGet(() -> insertInbound(tenantId, conversationId, waMessageId, body));
    }

    private Message insertInbound(UUID tenantId, UUID conversationId, String waMessageId, String body) {
        Conversation conversation = conversationService.getWithinTenant(tenantId, conversationId);
        Message message = Message.inbound(tenantId, conversationId, waMessageId, body);
        try {
            message = messageRepository.saveAndFlush(message);
        } catch (DataIntegrityViolationException raceLostToConcurrentDelivery) {
            return messageRepository.findByTenantIdAndWaMessageId(tenantId, waMessageId)
                    .orElseThrow(() -> raceLostToConcurrentDelivery);
        }
        conversation.recordInboundAt(Instant.now());
        return message;
    }

    @Transactional(readOnly = true)
    public Page<Message> listForConversation(UUID tenantId, UUID conversationId, Pageable pageable) {
        conversationService.getWithinTenant(tenantId, conversationId); // 404s if the conversation isn't in this tenant
        return messageRepository.findAllByTenantIdAndConversationId(tenantId, conversationId, pageable);
    }
}
