package com.supportplatform.whatsapp;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationService;
import com.supportplatform.customer.Customer;
import com.supportplatform.customer.CustomerService;
import com.supportplatform.message.Message;
import com.supportplatform.message.MessageService;
import com.supportplatform.notification.NotificationLogService;
import com.supportplatform.storage.Attachment;
import com.supportplatform.storage.AttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-event transactional worker for {@link InboundEventProcessor}. Split
 * out for the same reason {@link MessageDispatcher} is: one event failing
 * partway through must not roll back another event's already-processed
 * messages in the same poll batch. Re-fetches by id — the poller's read
 * happened in a separate, already-closed transaction.
 */
@Service
class WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventHandler.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final String FAILED_STATUS = "failed";
    /** Kept for the case Meta sends a failed status with no {@code errors} block, so the column is never null on a failure. */
    private static final String UNSPECIFIED_DELIVERY_FAILURE = "WhatsApp reported delivery failure";
    /** WhatsApp's four media categories (storage-domain.md §1 — stickers excluded, out of scope). */
    private static final Set<String> MEDIA_TYPES = Set.of("image", "document", "audio", "video");

    private final WebhookEventRepository webhookEventRepository;
    private final WhatsAppConnectionRepository connectionRepository;
    private final CustomerService customerService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final AttachmentService attachmentService;
    private final WhatsAppGateway gateway;
    private final NotificationLogService notificationLogService;
    private final ObjectMapper objectMapper;

    WebhookEventHandler(WebhookEventRepository webhookEventRepository, WhatsAppConnectionRepository connectionRepository,
                         CustomerService customerService, ConversationService conversationService,
                         MessageService messageService, AttachmentService attachmentService, WhatsAppGateway gateway,
                         NotificationLogService notificationLogService, ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.connectionRepository = connectionRepository;
        this.customerService = customerService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.gateway = gateway;
        this.notificationLogService = notificationLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    void handle(UUID eventId) {
        WebhookEvent event = webhookEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != WebhookEventStatus.PENDING) {
            return; // already handled by a previous tick, or gone
        }

        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            int changes = 0;
            int unmapped = 0;
            for (JsonNode entry : payload.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    changes++;
                    if (!processChange(change.path("value"))) {
                        unmapped++;
                    }
                }
            }

            // One delivery can carry changes for several phone numbers, and
            // an unmapped one says nothing about the others. Dropping the
            // whole event on the first miss silently discarded every change
            // behind it — DROPPED is terminal, so those were never retried
            // and the inbound message or status they carried was lost.
            if (changes > 0 && unmapped == changes) {
                event.drop("No WhatsApp connection mapped for the event's phone_number_id");
                return;
            }
            if (unmapped > 0) {
                log.warn("Webhook event {}: skipped {} of {} changes with an unmapped phone_number_id, processed the rest",
                        event.getId(), unmapped, changes);
            }
            event.markProcessed();
        } catch (Exception e) {
            int attempt = event.getAttemptCount() + 1;
            if (attempt >= MAX_ATTEMPTS) {
                log.warn("Webhook event {} failed permanently after {} attempts: {}", event.getId(), attempt, e.getMessage());
                event.markFailed(e.getMessage());
            } else {
                long backoffSeconds = (long) Math.pow(4, attempt);
                event.recordAttemptFailure(e.getMessage(), Instant.now().plusSeconds(backoffSeconds));
            }
        }
    }

    /** @return false if no tenant could be resolved for this change (the caller skips it, and drops the event only if every change missed). */
    private boolean processChange(JsonNode value) {
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
        if (phoneNumberId == null) {
            return true; // nothing tenant-scoped in this change (e.g. an account-level event) — not an error
        }

        Optional<WhatsAppConnection> connection = connectionRepository.findByPhoneNumberId(phoneNumberId);
        if (connection.isEmpty()) {
            return false;
        }

        String profileName = value.path("contacts").isEmpty() ? null
                : value.path("contacts").get(0).path("profile").path("name").asText(null);

        for (JsonNode message : value.path("messages")) {
            handleInboundMessage(connection.get(), message, profileName);
        }
        for (JsonNode status : value.path("statuses")) {
            handleStatusUpdate(connection.get().getTenantId(), status);
        }
        return true;
    }

    private void handleInboundMessage(WhatsAppConnection connection, JsonNode message, String profileName) {
        UUID tenantId = connection.getTenantId();
        String waMessageId = message.path("id").asText();

        if (messageService.existsByWaMessageId(tenantId, waMessageId)) {
            return; // redelivered event — skip re-resolving the customer/conversation and, for media, re-downloading it
        }

        String from = message.path("from").asText();
        String type = message.path("type").asText("text");

        Customer customer = customerService.findOrCreateFromInbound(tenantId, "+" + from, profileName);
        Conversation conversation = conversationService.findOrOpenForCustomer(tenantId, customer.getId());

        if (MEDIA_TYPES.contains(type)) {
            handleInboundMedia(connection, conversation.getId(), message, type, waMessageId);
        } else {
            String body = message.path("text").path("body").asText("");
            messageService.recordInbound(tenantId, conversation.getId(), waMessageId, body);
        }
    }

    private void handleInboundMedia(WhatsAppConnection connection, UUID conversationId, JsonNode message, String type, String waMessageId) {
        JsonNode mediaNode = message.path(type);
        String mediaId = mediaNode.path("id").asText();
        String caption = mediaNode.path("caption").asText("");
        String fileName = mediaNode.path("filename").asText(null);

        DownloadedMedia media = gateway.downloadMedia(connection, mediaId);
        Attachment attachment = attachmentService.upload(connection.getTenantId(), media.content(), media.contentType(), fileName);

        Message savedMessage = messageService.recordInbound(connection.getTenantId(), conversationId, waMessageId, caption);
        attachment.linkToMessage(savedMessage.getId());
    }

    /**
     * A status event can refer to either kind of outbound send: an agent's
     * conversation message ({@code message}) or an API-driven notification
     * ({@code notification_log}). Meta gives us one id space for both, so
     * try the conversation message first — much the commoner case — and
     * fall through to notifications. Only an id in neither is genuinely
     * unrecognized, and is logged and dropped rather than guessed at, the
     * same as an unmapped {@code phone_number_id}.
     */
    private void handleStatusUpdate(UUID tenantId, JsonNode status) {
        String waMessageId = status.path("id").asText();
        String metaStatus = status.path("status").asText();
        String failureReason = FAILED_STATUS.equals(metaStatus) ? describeStatusError(status) : null;

        if (failureReason != null) {
            // The only place this reason is ever visible outside the row we
            // are about to write, and the one line worth grepping for when a
            // recipient reports a message that never arrived.
            log.warn("WhatsApp reported delivery failure for {} in tenant {}: {}", waMessageId, tenantId, failureReason);
        }

        if (messageService.applyDeliveryStatus(tenantId, waMessageId, metaStatus, failureReason)) {
            return;
        }
        if (notificationLogService.applyDeliveryStatus(tenantId, waMessageId, metaStatus, failureReason)) {
            return;
        }
        log.warn("Status webhook for unrecognized wa_message_id {} in tenant {}", waMessageId, tenantId);
    }

    /**
     * Flattens Meta's {@code errors[0]} into the one line we keep as
     * {@code failure_reason}.
     *
     * <p>This is the difference between "it failed" and a diagnosis: the
     * code distinguishes per-recipient throttling (131049) from an
     * unreachable number (131026) from a template Meta has paused (132015),
     * and each of those wants a different response from us. Meta sends it on
     * every failed status; we used to store a constant string instead, which
     * made an undelivered message indistinguishable from any other and left
     * the raw payload in {@code webhook_event} as the only copy.
     *
     * <p>Falls back to the old constant when the array is absent, so a
     * failure with no error block still reads as a failure rather than null.
     */
    private String describeStatusError(JsonNode status) {
        JsonNode error = status.path("errors").path(0);
        String code = error.path("code").asText(null);
        String title = error.path("title").asText(null);
        String details = error.path("error_data").path("details").asText(null);

        StringBuilder reason = new StringBuilder();
        if (code != null && !code.isBlank()) {
            reason.append("Meta error ").append(code);
        }
        if (title != null && !title.isBlank()) {
            reason.append(reason.isEmpty() ? "" : ": ").append(title);
        }
        if (details != null && !details.isBlank()) {
            reason.append(reason.isEmpty() ? "" : " - ").append(details);
        }
        return reason.isEmpty() ? UNSPECIFIED_DELIVERY_FAILURE : reason.toString();
    }
}
