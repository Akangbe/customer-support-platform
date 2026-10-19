package com.supportplatform.whatsapp;

import com.supportplatform.email.EmailGateway;
import com.supportplatform.message.MessageService;
import com.supportplatform.notification.NotificationLog;
import com.supportplatform.notification.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The alert that would have caught the 2026-09-05 billing outage on its
 * first failed message instead of on the first customer complaint.
 */
class DeliveryBlockedAlertTest extends AbstractWhatsAppIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @MockitoBean
    private EmailGateway emailGateway;

    @Autowired
    private InboundEventProcessor inboundEventProcessor;
    @Autowired
    private MessageService messageService;
    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Test
    void aPaymentBlockAlertsTheOwnerWithMetasExplanation() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Block Co 1", "Block Owner 1", "block-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "block-pn-1");
        seedNotification(tenantId, "wamid.BLOCK-1");

        postSignedWebhook(failedStatusPayload(phoneNumberId, "wamid.BLOCK-1", 131042,
                "Business eligibility payment issue",
                "Message failed to send because your WhatsApp Business account has unsettled payments."));
        inboundEventProcessor.processPending();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailGateway).send(eq("block-owner-1@example.com"), anyString(), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("131042")
                .contains("EVERY message")
                .contains("unsettled payments");
    }

    /**
     * The property that makes this safe to leave switched on: while an
     * account is blocked every message fails, and one email per failed
     * message would be thousands of them from a single outage.
     */
    @Test
    void repeatedBlockedDeliveriesRaiseOneAlertNotOnePerMessage() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Block Co 2", "Block Owner 2", "block-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "block-pn-2");

        for (int i = 0; i < 4; i++) {
            String waMessageId = "wamid.BLOCK-2-" + i;
            seedNotification(tenantId, waMessageId);
            postSignedWebhook(failedStatusPayload(phoneNumberId, waMessageId, 131042,
                    "Business eligibility payment issue", "unsettled payments"));
            inboundEventProcessor.processPending();
        }

        verify(emailGateway, times(1)).send(eq("block-owner-2@example.com"), anyString(), anyString(), anyString());
    }

    /**
     * An integrator sending through the number loses every message too, so
     * each address on an active key hears about it -- but not the contact
     * of a key that has been switched off.
     */
    @Test
    void aBlockAlsoAlertsEveryActiveKeysContact() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Block Co 5", "Block Owner 5", "block-owner-5@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "block-pn-5");
        issueKey(owner, "Trustpady production", "dev@integrator-5.example.com, ops@integrator-5.example.com");
        String retired = issueKey(owner, "Old client", "old@retired-5.example.com");
        mockMvc.perform(post("/api/v1/api-keys/" + retired + "/deactivate").session(owner))
                .andExpect(status().is2xxSuccessful());
        seedNotification(tenantId, "wamid.BLOCK-5");

        postSignedWebhook(failedStatusPayload(phoneNumberId, "wamid.BLOCK-5", 131042,
                "Business eligibility payment issue", "unsettled payments"));
        inboundEventProcessor.processPending();

        verify(emailGateway).send(eq("block-owner-5@example.com"), anyString(), anyString(), anyString());
        verify(emailGateway).send(eq("dev@integrator-5.example.com"), anyString(), anyString(), anyString());
        verify(emailGateway).send(eq("ops@integrator-5.example.com"), anyString(), anyString(), anyString());
        verify(emailGateway, never()).send(eq("old@retired-5.example.com"), anyString(), anyString(), anyString());
    }

    /** An ordinary per-recipient failure is background noise and must never page anyone. */
    @Test
    void anOrdinaryDeliveryFailureRaisesNoAlert() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Block Co 3", "Block Owner 3", "block-owner-3@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "block-pn-3");
        seedNotification(tenantId, "wamid.BLOCK-3");

        postSignedWebhook(failedStatusPayload(phoneNumberId, "wamid.BLOCK-3", 131049,
                "Healthy ecosystem engagement",
                "This message was not delivered to maintain healthy ecosystem engagement."));
        inboundEventProcessor.processPending();

        verify(emailGateway, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    /** A paused template blocks one template, not the number — and the alert has to say which. */
    @Test
    void aPausedTemplateAlertNamesTheTemplateAndNotTheWholeAccount() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Block Co 4", "Block Owner 4", "block-owner-4@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "block-pn-4");
        seedNotification(tenantId, "wamid.BLOCK-4");

        postSignedWebhook(failedStatusPayload(phoneNumberId, "wamid.BLOCK-4", 132015,
                "Template paused", "Template was paused due to low quality."));
        inboundEventProcessor.processPending();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailGateway).send(eq("block-owner-4@example.com"), anyString(), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("132015")
                .contains("trustpady_notification_utility")
                .doesNotContain("EVERY message");
    }

    /** @return the new key's id */
    private String issueKey(MockHttpSession owner, String name, String contactEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/api-keys")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"contactEmail\":\"%s\"}".formatted(name, contactEmail)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("key").get("id").asText();
    }

    private void seedNotification(UUID tenantId, String waMessageId) {
        notificationLogRepository.save(NotificationLog.sent(tenantId, null, "+2348159103556",
                "trustpady_notification_utility", "en", waMessageId));
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
                                "id": "%s", "status": "failed", "timestamp": "1700000002", "recipient_id": "2348159103556",
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
}
