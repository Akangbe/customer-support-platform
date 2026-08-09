package com.supportplatform.whatsapp;

import com.supportplatform.conversation.Conversation;
import com.supportplatform.conversation.ConversationRepository;
import com.supportplatform.conversation.ConversationStatus;
import com.supportplatform.customer.Customer;
import com.supportplatform.customer.CustomerRepository;
import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers whatsapp-domain.md §4-5: webhook → WebhookEvent → poller →
 * tenant resolution → the idempotent Customer/Conversation/Message
 * methods Phases 3-5 built ahead of this exact caller.
 */
class InboundProcessingTest extends AbstractWhatsAppIntegrationTest {

    @Autowired
    private InboundEventProcessor inboundEventProcessor;
    @Autowired
    private WebhookEventRepository webhookEventRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;

    @Test
    void anInboundMessageCreatesCustomerConversationAndMessage() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Inbound Co 1", "Inbound Owner 1", "inbound-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "in-pn-1001");

        postSignedWebhook(inboundMessagePayload(phoneNumberId, "15551110001", "wamid.IN1", "Hello, I need help", "Jane Doe"));
        inboundEventProcessor.processPending();

        Customer customer = customerRepository.findByTenantIdAndPhone(tenantId, "+15551110001").orElseThrow();
        assertThat(customer.getName()).isEqualTo("Jane Doe");

        List<Conversation> conversations = conversationRepository.findAllByTenantId(tenantId, Pageable.unpaged()).getContent();
        assertThat(conversations).hasSize(1);
        assertThat(conversations.get(0).getStatus()).isEqualTo(ConversationStatus.OPEN);
        assertThat(conversations.get(0).getLastInboundAt()).isNotNull();

        Message message = messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.IN1").orElseThrow();
        assertThat(message.getBody()).isEqualTo("Hello, I need help");

        WebhookEvent event = webhookEventRepository.findAll().stream()
                .filter(e -> e.getPayload().contains("wamid.IN1"))
                .findFirst().orElseThrow();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void redeliveringTheSamePayloadDoesNotCreateADuplicateMessage() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Inbound Co 2", "Inbound Owner 2", "inbound-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "in-pn-1002");
        String payload = inboundMessagePayload(phoneNumberId, "15551110002", "wamid.IN2", "Duplicate me", null);

        postSignedWebhook(payload);
        postSignedWebhook(payload);
        inboundEventProcessor.processPending();

        List<Message> messages = messageRepository.findAllByTenantIdAndConversationId(
                tenantId, conversationRepository.findAllByTenantId(tenantId, Pageable.unpaged()).getContent().get(0).getId(),
                Pageable.unpaged()).getContent();
        assertThat(messages).hasSize(1);
    }

    @Test
    void anEventForAnUnknownPhoneNumberIdIsDroppedNotGuessed() throws Exception {
        long customersBefore = customerRepository.count();

        postSignedWebhook(inboundMessagePayload("no-such-phone-number-id", "15551110099", "wamid.IN3", "Nobody's tenant", null));
        inboundEventProcessor.processPending();

        assertThat(customerRepository.count()).isEqualTo(customersBefore);
        WebhookEvent event = webhookEventRepository.findAll().stream()
                .filter(e -> e.getPayload().contains("wamid.IN3"))
                .findFirst().orElseThrow();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.DROPPED);
    }

    private String inboundMessagePayload(String phoneNumberId, String fromDigits, String waMessageId, String body, String profileName) {
        String contactsJson = profileName == null
                ? "[]"
                : """
                  [{"profile":{"name":"%s"},"wa_id":"%s"}]
                  """.formatted(profileName, fromDigits);

        return """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "waba-1",
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "metadata": {"display_phone_number": "15550000000", "phone_number_id": "%s"},
                            "contacts": %s,
                            "messages": [
                              {"from": "%s", "id": "%s", "timestamp": "1700000000", "type": "text", "text": {"body": "%s"}}
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(phoneNumberId, contactsJson, fromDigits, waMessageId, body);
    }
}
