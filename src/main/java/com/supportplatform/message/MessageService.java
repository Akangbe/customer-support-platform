package com.supportplatform.message;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationService;
import com.supportplatform.conversation.ConversationStatus;
import com.supportplatform.conversation.InvalidConversationStateException;
import com.supportplatform.storage.Attachment;
import com.supportplatform.storage.AttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Message persistence and the 24-hour service-window rule
 * (message-domain.md). Tenant scoping flows through
 * {@link ConversationService#getWithinTenant}, the same 404-on-guess
 * protection every other tenant-owned resource uses. Every method that
 * creates or transitions a message publishes {@link MessageEvent} — a
 * plain Spring application event, so this service stays completely
 * unaware that WebSocket exists (realtime-domain.md §4).
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final Duration SERVICE_WINDOW = Duration.ofHours(24);

    private final MessageRepository messageRepository;
    private final ConversationService conversationService;
    private final AttachmentService attachmentService;
    private final ApplicationEventPublisher eventPublisher;

    public MessageService(MessageRepository messageRepository, ConversationService conversationService,
                           AttachmentService attachmentService, ApplicationEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.conversationService = conversationService;
        this.attachmentService = attachmentService;
        this.eventPublisher = eventPublisher;
    }

    /** An agent replying free-form. Persists PENDING and stops there — the outbound sender (whatsapp-domain.md §6) is what actually reaches WhatsApp. */
    @Transactional
    public Message sendOutbound(UUID tenantId, UUID conversationId, UUID senderUserId, String body) {
        return sendOutbound(tenantId, conversationId, senderUserId, body, null, null, List.of(), null);
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
        return sendOutbound(tenantId, conversationId, senderUserId, body, templateName, templateLanguageCode, templateParams, null);
    }

    /**
     * Same as above, plus an optional {@code attachmentId} (storage-domain.md
     * §4) — WhatsApp allows a media message with no caption, so {@code body}
     * alone is no longer required; a request with neither body, template,
     * nor attachment is rejected.
     */
    @Transactional
    public Message sendOutbound(UUID tenantId, UUID conversationId, UUID senderUserId, String body,
                                 String templateName, String templateLanguageCode, List<String> templateParams,
                                 UUID attachmentId) {
        if (templateName != null && (templateLanguageCode == null || templateLanguageCode.isBlank())) {
            throw new ResponseStatusException(BAD_REQUEST, "templateLanguageCode is required when templateName is provided");
        }
        if (attachmentId == null && templateName == null && (body == null || body.isBlank())) {
            throw new ResponseStatusException(BAD_REQUEST, "A message needs a body, a template, or an attachment");
        }
        // body is NOT NULL at the DB level; a no-caption media send normalizes to "", matching the inbound convention (whatsapp-domain.md).
        String normalizedBody = body == null ? "" : body;

        Conversation conversation = conversationService.getWithinTenant(tenantId, conversationId);

        if (conversation.getStatus() == ConversationStatus.CLOSED) {
            throw new InvalidConversationStateException("Cannot send a message on a closed conversation; reopen it first");
        }

        boolean windowOpen = isWindowOpen(conversation);
        if (!windowOpen && templateName == null) {
            throw new OutsideServiceWindowException(
                    "Outside the 24-hour customer-service window; a template message is required");
        }

        Attachment attachment = attachmentId == null ? null : attachmentService.getWithinTenant(tenantId, attachmentId);
        if (attachment != null && attachment.getMessageId() != null) {
            throw new ResponseStatusException(CONFLICT, "This attachment is already linked to a message");
        }

        Message message = templateName != null
                ? Message.outboundTemplate(tenantId, conversationId, senderUserId, normalizedBody, templateName, templateLanguageCode, templateParams)
                : Message.outbound(tenantId, conversationId, senderUserId, normalizedBody);
        conversation.recordOutboundAt(Instant.now());
        message = messageRepository.save(message);

        if (attachment != null) {
            attachment.linkToMessage(message.getId());
        }

        publishEvent(tenantId, conversationId, message.getId());
        return message;
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
            publishEvent(tenantId, message.getConversationId(), message.getId());
        }, () -> log.warn("Status webhook for unrecognized wa_message_id {} in tenant {}", waMessageId, tenantId));
    }

    /** A cheap existence check for the inbound media path (storage-domain.md §6) — lets the caller skip a download/upload entirely on a redelivered event, before recordInbound's own dedupe would otherwise run. */
    @Transactional(readOnly = true)
    public boolean existsByWaMessageId(UUID tenantId, String waMessageId) {
        return messageRepository.existsByTenantIdAndWaMessageId(tenantId, waMessageId);
    }

    @Transactional(readOnly = true)
    public Message getWithinTenant(UUID tenantId, UUID messageId) {
        return messageRepository.findByIdAndTenantId(messageId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Message not found"));
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
        publishEvent(tenantId, conversationId, message.getId());
        return message;
    }

    private void publishEvent(UUID tenantId, UUID conversationId, UUID messageId) {
        eventPublisher.publishEvent(new MessageEvent(tenantId, conversationId, messageId));
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
