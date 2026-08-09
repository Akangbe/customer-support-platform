package com.supportplatform.whatsapp;

import com.supportplatform.message.MessageRepository;
import com.supportplatform.message.MessageService;
import com.supportplatform.message.MessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers whatsapp-domain.md §7: a status webhook driving Message's SENT → DELIVERED → READ transitions. */
class StatusUpdateTest extends AbstractWhatsAppIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private OutboundMessageSender outboundMessageSender;
    @Autowired
    private InboundEventProcessor inboundEventProcessor;
    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Test
    void aStatusWebhookAdvancesTheMessageFromSentToDeliveredToRead() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Status Co 1", "Status Owner 1", "status-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "status-pn-1001");
        String customerId = createCustomer(owner, "+14155558001", "Status Customer 1");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.SEED-STATUS-1", "hi");

        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.success("wamid.OUT-STATUS-1"));
        sendOutbound(owner, conversationId, "On our way");
        outboundMessageSender.sendPending();

        postSignedWebhook(statusPayload(phoneNumberId, "wamid.OUT-STATUS-1", "delivered"));
        inboundEventProcessor.processPending();
        assertThat(messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.OUT-STATUS-1").orElseThrow().getStatus())
                .isEqualTo(MessageStatus.DELIVERED);

        postSignedWebhook(statusPayload(phoneNumberId, "wamid.OUT-STATUS-1", "read"));
        inboundEventProcessor.processPending();
        assertThat(messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.OUT-STATUS-1").orElseThrow().getStatus())
                .isEqualTo(MessageStatus.READ);
    }

    @Test
    void aStatusForAnUnrecognizedMessageIdIsLoggedAndTheEventStillProcesses() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Status Co 2", "Status Owner 2", "status-owner-2@example.com", "password123");
        String phoneNumberId = connectWhatsApp(owner, "status-pn-1002");

        postSignedWebhook(statusPayload(phoneNumberId, "wamid.NEVER-SENT", "delivered"));
        inboundEventProcessor.processPending();

        WebhookEvent event = webhookEventRepository.findAll().stream()
                .filter(e -> e.getPayload().contains("wamid.NEVER-SENT"))
                .findFirst().orElseThrow();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    private void sendOutbound(MockHttpSession session, String conversationId, String body) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated());
    }

    private String statusPayload(String phoneNumberId, String waMessageId, String statusValue) {
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
                            "metadata": {"phone_number_id": "%s"},
                            "statuses": [
                              {"id": "%s", "status": "%s", "timestamp": "1700000002", "recipient_id": "15550000000"}
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(phoneNumberId, waMessageId, statusValue);
    }
}
