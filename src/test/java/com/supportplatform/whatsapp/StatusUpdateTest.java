package com.supportplatform.whatsapp;

import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import com.supportplatform.message.MessageService;
import com.supportplatform.message.MessageStatus;
import com.supportplatform.notification.NotificationLog;
import com.supportplatform.notification.NotificationLogRepository;
import com.supportplatform.notification.NotificationStatus;
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
    @Autowired
    private NotificationLogRepository notificationLogRepository;

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

    /**
     * Meta's {@code errors[0]} is the whole diagnosis for an undelivered
     * message — 131049 (per-recipient throttling) reads nothing like 131026
     * (unreachable number), and both used to land as the same constant
     * string, leaving the raw webhook payload as the only copy.
     */
    @Test
    void aFailedStatusRecordsMetasOwnErrorRatherThanAConstant() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Status Co 3", "Status Owner 3", "status-owner-3@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "status-pn-1003");
        String customerId = createCustomer(owner, "+14155558003", "Status Customer 3");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.SEED-STATUS-3", "hi");

        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.success("wamid.OUT-STATUS-3"));
        sendOutbound(owner, conversationId, "On our way");
        outboundMessageSender.sendPending();

        postSignedWebhook(failedStatusPayload(phoneNumberId, "wamid.OUT-STATUS-3", 131049,
                "Message failed to send because of unknown problems with sending the message",
                "This message was not delivered to maintain healthy ecosystem engagement."));
        inboundEventProcessor.processPending();

        Message failed = messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.OUT-STATUS-3").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.getFailureReason())
                .contains("131049")
                .contains("healthy ecosystem engagement");
    }

    /**
     * The same recovery on the notification-API path — the one Trustpady
     * sends through, and the one the live incident was on. Seeded straight
     * through the repository because a notification row needs only an API
     * key's tenant, not a conversation or a customer.
     */
    @Test
    void aFailedStatusRecordsMetasOwnErrorOnANotificationToo() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Status Co 6", "Status Owner 6", "status-owner-6@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "status-pn-1006");
        notificationLogRepository.save(NotificationLog.sent(tenantId, null, "+2348159103556",
                "trustpady_notification_utility", "en", "wamid.NOTIF-STATUS-6"));

        postSignedWebhook(failedStatusPayload(phoneNumberId, "wamid.NOTIF-STATUS-6", 131049,
                "Message failed to send because of unknown problems with sending the message",
                "This message was not delivered to maintain healthy ecosystem engagement."));
        inboundEventProcessor.processPending();

        NotificationLog notification = notificationLogRepository
                .findByTenantIdAndMetaMessageId(tenantId, "wamid.NOTIF-STATUS-6").orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason())
                .contains("131049")
                .contains("healthy ecosystem engagement");
    }

    /** A failed status with no {@code errors} block still has to read as a failure, not a null reason. */
    @Test
    void aFailedStatusWithNoErrorBlockFallsBackToTheGenericReason() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Status Co 4", "Status Owner 4", "status-owner-4@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "status-pn-1004");
        String customerId = createCustomer(owner, "+14155558004", "Status Customer 4");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.SEED-STATUS-4", "hi");

        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.success("wamid.OUT-STATUS-4"));
        sendOutbound(owner, conversationId, "On our way");
        outboundMessageSender.sendPending();

        postSignedWebhook(statusPayload(phoneNumberId, "wamid.OUT-STATUS-4", "failed"));
        inboundEventProcessor.processPending();

        Message failed = messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.OUT-STATUS-4").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("WhatsApp reported delivery failure");
    }

    /**
     * One delivery can carry changes for several phone numbers. An unmapped
     * one used to drop the entire event on the spot, discarding every change
     * behind it — and DROPPED is terminal, so they were never retried.
     */
    @Test
    void anUnmappedPhoneNumberDoesNotDiscardTheRestOfTheBatch() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Status Co 5", "Status Owner 5", "status-owner-5@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "status-pn-1005");
        String customerId = createCustomer(owner, "+14155558005", "Status Customer 5");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.SEED-STATUS-5", "hi");

        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.success("wamid.OUT-STATUS-5"));
        sendOutbound(owner, conversationId, "On our way");
        outboundMessageSender.sendPending();

        // Unmapped number first, so the real change sits behind the miss.
        postSignedWebhook(twoChangePayload("pn-belongs-to-nobody", phoneNumberId, "wamid.OUT-STATUS-5", "delivered"));
        inboundEventProcessor.processPending();

        assertThat(messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.OUT-STATUS-5").orElseThrow().getStatus())
                .isEqualTo(MessageStatus.DELIVERED);

        WebhookEvent event = webhookEventRepository.findAll().stream()
                .filter(e -> e.getPayload().contains("pn-belongs-to-nobody"))
                .findFirst().orElseThrow();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    /** Every change unmapped is still a drop — there was nothing here for us. */
    @Test
    void anEventWhoseChangesAreAllUnmappedIsStillDropped() throws Exception {
        postSignedWebhook(statusPayload("pn-nobody-at-all", "wamid.ORPHAN-1", "delivered"));
        inboundEventProcessor.processPending();

        WebhookEvent event = webhookEventRepository.findAll().stream()
                .filter(e -> e.getPayload().contains("pn-nobody-at-all"))
                .findFirst().orElseThrow();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.DROPPED);
    }

    private void sendOutbound(MockHttpSession session, String conversationId, String body) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated());
    }

    private String failedStatusPayload(String phoneNumberId, String waMessageId, int code, String title, String details) {
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
                              {
                                "id": "%s", "status": "failed", "timestamp": "1700000002", "recipient_id": "15550000000",
                                "errors": [
                                  {"code": %d, "title": "%s", "message": "%s", "error_data": {"details": "%s"}}
                                ]
                              }
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(phoneNumberId, waMessageId, code, title, title, details);
    }

    /** Two changes in one delivery, the first for a phone number we have no connection for. */
    private String twoChangePayload(String unmappedPhoneNumberId, String phoneNumberId, String waMessageId, String statusValue) {
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
                              {"id": "wamid.NOT-OURS", "status": "delivered", "timestamp": "1700000001", "recipient_id": "15550000000"}
                            ]
                          },
                          "field": "messages"
                        },
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
                """.formatted(unmappedPhoneNumberId, phoneNumberId, waMessageId, statusValue);
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
