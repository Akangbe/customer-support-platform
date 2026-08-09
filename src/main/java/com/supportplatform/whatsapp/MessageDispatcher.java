package com.supportplatform.whatsapp;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationService;
import com.supportplatform.customer.Customer;
import com.supportplatform.customer.CustomerService;
import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import com.supportplatform.message.MessageStatus;
import com.supportplatform.storage.Attachment;
import com.supportplatform.storage.AttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-message transactional worker for {@link OutboundMessageSender}.
 * Split out so each message in a poll batch commits (or backs off)
 * independently — one message hitting an unexpected error must not roll
 * back the successful sends the same batch already made. Re-fetches by
 * id rather than accepting the entity the poller read, since that read
 * happened in a separate (already-closed) transaction.
 */
@Service
class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);
    private static final int MAX_ATTEMPTS = 5;

    private final MessageRepository messageRepository;
    private final WhatsAppConnectionRepository connectionRepository;
    private final ConversationService conversationService;
    private final CustomerService customerService;
    private final AttachmentService attachmentService;
    private final WhatsAppGateway gateway;

    MessageDispatcher(MessageRepository messageRepository, WhatsAppConnectionRepository connectionRepository,
                       ConversationService conversationService, CustomerService customerService,
                       AttachmentService attachmentService, WhatsAppGateway gateway) {
        this.messageRepository = messageRepository;
        this.connectionRepository = connectionRepository;
        this.conversationService = conversationService;
        this.customerService = customerService;
        this.attachmentService = attachmentService;
        this.gateway = gateway;
    }

    @Transactional
    void dispatch(UUID messageId) {
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getStatus() != MessageStatus.PENDING) {
            return; // already handled by a previous tick, or gone
        }

        Optional<WhatsAppConnection> connection = connectionRepository.findByTenantId(message.getTenantId());
        if (connection.isEmpty()) {
            // Not connected is a permanent condition, not a transient failure — no point retrying.
            message.markFailed("WhatsApp is not connected for this tenant");
            return;
        }

        Conversation conversation = conversationService.getWithinTenant(message.getTenantId(), message.getConversationId());
        Customer customer = customerService.getWithinTenant(message.getTenantId(), conversation.getCustomerId());
        Optional<Attachment> attachment = attachmentService.findByMessageId(message.getId());

        SendResult result = send(connection.get(), customer, message, attachment.orElse(null));

        if (result.success()) {
            message.markSent(result.waMessageId());
            return;
        }

        int attempt = message.getAttemptCount() + 1;
        if (attempt >= MAX_ATTEMPTS) {
            log.warn("Message {} failed permanently after {} attempts: {}", message.getId(), attempt, result.errorDetail());
            message.markFailed(result.errorDetail());
        } else {
            long backoffSeconds = (long) Math.pow(4, attempt);
            message.recordSendAttemptFailure(result.errorDetail(), Instant.now().plusSeconds(backoffSeconds));
        }
    }

    private SendResult send(WhatsAppConnection connection, Customer customer, Message message, Attachment attachment) {
        if (attachment != null) {
            URI link = attachmentService.presignedUrlFor(attachment);
            return gateway.sendMedia(connection, customer.getPhone(), mediaTypeFor(attachment.getContentType()), link, message.getBody());
        }
        if (message.getTemplateName() != null) {
            return gateway.sendTemplate(connection, customer.getPhone(), message.getTemplateName(),
                    message.getTemplateLanguageCode(), message.getTemplateParams());
        }
        return gateway.sendText(connection, customer.getPhone(), message.getBody());
    }

    /** WhatsApp's four media categories (storage-domain.md §1 — stickers excluded, out of scope). */
    private String mediaTypeFor(String contentType) {
        if (contentType == null) {
            return "document";
        }
        if (contentType.startsWith("image/")) {
            return "image";
        }
        if (contentType.startsWith("video/")) {
            return "video";
        }
        if (contentType.startsWith("audio/")) {
            return "audio";
        }
        return "document";
    }
}
