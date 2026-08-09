package com.supportplatform.message;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationService;
import com.supportplatform.conversation.ConversationStatus;
import com.supportplatform.conversation.InvalidConversationStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Message persistence and the 24-hour service-window rule
 * (message-domain.md). Tenant scoping flows through
 * {@link ConversationService#getWithinTenant}, the same 404-on-guess
 * protection every other tenant-owned resource uses.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final Duration SERVICE_WINDOW = Duration.ofHours(24);

    private final MessageRepository messageRepository;
    private final ConversationService conversationService;

    public MessageService(MessageRepository messageRepository, ConversationService conversationService) {
        this.messageRepository = messageRepository;
        this.conversationService = conversationService;
    }

    /** An agent replying free-form. Persists PENDING and stops there — the outbound sender (whatsapp-domain.md §6) is what actually reaches WhatsApp. */
    @Transactional
    public Message sendOutbound(UUID tenantId, UUID conversationId, UUID senderUserId, String body) {
        return sendOutbound(tenantId, conversationId, senderUserId, body, null, null, List.of());
    }

    /**
     * Same as {@link #sendOutbound(UUID, UUID, UUID, String)}, but allows a
     * template to unblock a send outside the 24h window (whatsapp-domain.md
     * §8) — the alternative to rejection that message-domain.md §3 promised.
     * A template may also be supplied inside the window; Meta allows that,
     * it just sends via template instead of free text.
     */
    @Transactional
    public Message sendOutbound(UUID tenantId, UUID conversationId, UUID senderUserId, String body,
                                 String templateName, String templateLanguageCode, List<String> templateParams) {
        if (templateName != null && (templateLanguageCode == null || templateLanguageCode.isBlank())) {
            throw new ResponseStatusException(BAD_REQUEST, "templateLanguageCode is required when templateName is provided");
        }

        Conversation conversation = conversationService.getWithinTenant(tenantId, conversationId);

        if (conversation.getStatus() == ConversationStatus.CLOSED) {
            throw new InvalidConversationStateException("Cannot send a message on a closed conversation; reopen it first");
        }

        boolean windowOpen = isWindowOpen(conversation);
        if (!windowOpen && templateName == null) {
            throw new OutsideServiceWindowException(
                    "Outside the 24-hour customer-service window; a template message is required");
        }

        Message message = templateName != null
                ? Message.outboundTemplate(tenantId, conversationId, senderUserId, body, templateName, templateLanguageCode, templateParams)
                : Message.outbound(tenantId, conversationId, senderUserId, body);
        conversation.recordOutboundAt(Instant.now());
        return messageRepository.save(message);
    }

    /**
     * Idempotent on {@code (tenantId, waMessageId)} (ADR-012) — safe to
     * call more than once for the same Meta message id. Not reachable
     * over HTTP directly; the inbound event processor (whatsapp-domain.md
     * §4) is the caller.
     */
    @Transactional
    public Message recordInbound(UUID tenantId, UUID conversationId, String waMessageId, String body) {
        return messageRepository.findByTenantIdAndWaMessageId(tenantId, waMessageId)
                .orElseGet(() -> insertInbound(tenantId, conversationId, waMessageId, body));
    }

    /**
     * Applies a WhatsApp status-webhook event (sent/delivered/read/failed)
     * to the outbound message it refers to (whatsapp-domain.md §7). A
     * {@code wa_message_id} we don't recognize is logged and dropped —
     * never guessed at, same as an unmapped {@code phone_number_id}.
     */
    @Transactional
    public void applyDeliveryStatus(UUID tenantId, String waMessageId, String metaStatus) {
        messageRepository.findByTenantIdAndWaMessageId(tenantId, waMessageId).ifPresentOrElse(message -> {
            switch (metaStatus) {
                case "delivered" -> message.markDelivered();
                case "read" -> message.markRead();
                case "failed" -> message.markFailed("WhatsApp reported delivery failure");
                case "sent" -> { /* already SENT when we dispatched it; nothing to do */ }
                default -> log.warn("Unrecognized WhatsApp status '{}' for message {}", metaStatus, waMessageId);
            }
        }, () -> log.warn("Status webhook for unrecognized wa_message_id {} in tenant {}", waMessageId, tenantId));
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

    private boolean isWindowOpen(Conversation conversation) {
        Instant lastInboundAt = conversation.getLastInboundAt();
        return lastInboundAt != null && !lastInboundAt.isBefore(Instant.now().minus(SERVICE_WINDOW));
    }

    @Transactional(readOnly = true)
    public Page<Message> listForConversation(UUID tenantId, UUID conversationId, Pageable pageable) {
        conversationService.getWithinTenant(tenantId, conversationId); // 404s if the conversation isn't in this tenant
        return messageRepository.findAllByTenantIdAndConversationId(tenantId, conversationId, pageable);
    }
}
