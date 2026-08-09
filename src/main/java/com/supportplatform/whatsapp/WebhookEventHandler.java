package com.supportplatform.whatsapp;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationService;
import com.supportplatform.customer.Customer;
import com.supportplatform.customer.CustomerService;
import com.supportplatform.message.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
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

    private final WebhookEventRepository webhookEventRepository;
    private final WhatsAppConnectionRepository connectionRepository;
    private final CustomerService customerService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    WebhookEventHandler(WebhookEventRepository webhookEventRepository, WhatsAppConnectionRepository connectionRepository,
                         CustomerService customerService, ConversationService conversationService,
                         MessageService messageService, ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.connectionRepository = connectionRepository;
        this.customerService = customerService;
        this.conversationService = conversationService;
        this.messageService = messageService;
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
            for (JsonNode entry : payload.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    if (!processChange(change.path("value"))) {
                        event.drop("No WhatsApp connection mapped for the event's phone_number_id");
                        return;
                    }
                }
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

    /** @return false if no tenant could be resolved for this change (caller drops the whole event). */
    private boolean processChange(JsonNode value) {
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
        if (phoneNumberId == null) {
            return true; // nothing tenant-scoped in this change (e.g. an account-level event) — not an error
        }

        Optional<WhatsAppConnection> connection = connectionRepository.findByPhoneNumberId(phoneNumberId);
        if (connection.isEmpty()) {
            return false;
        }
        UUID tenantId = connection.get().getTenantId();

        String profileName = value.path("contacts").isEmpty() ? null
                : value.path("contacts").get(0).path("profile").path("name").asText(null);

        for (JsonNode message : value.path("messages")) {
            handleInboundMessage(tenantId, message, profileName);
        }
        for (JsonNode status : value.path("statuses")) {
            handleStatusUpdate(tenantId, status);
        }
        return true;
    }

    private void handleInboundMessage(UUID tenantId, JsonNode message, String profileName) {
        String from = message.path("from").asText();
        String waMessageId = message.path("id").asText();
        String body = message.path("text").path("body").asText("");

        Customer customer = customerService.findOrCreateFromInbound(tenantId, "+" + from, profileName);
        Conversation conversation = conversationService.findOrOpenForCustomer(tenantId, customer.getId());
        messageService.recordInbound(tenantId, conversation.getId(), waMessageId, body);
    }

    private void handleStatusUpdate(UUID tenantId, JsonNode status) {
        String waMessageId = status.path("id").asText();
        String metaStatus = status.path("status").asText();
        messageService.applyDeliveryStatus(tenantId, waMessageId, metaStatus);
    }
}
