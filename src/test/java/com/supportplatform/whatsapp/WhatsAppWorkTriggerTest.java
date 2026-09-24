package com.supportplatform.whatsapp;

import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import com.supportplatform.message.MessageService;
import com.supportplatform.message.MessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scheduler switched on, end to end: work is done because it arrived,
 * not because anyone called a processor. Both sweeps are pushed an hour
 * out, so arrival and retry timing are the only triggers in play — which
 * is exactly the situation that must work if the two-second poll is gone.
 */
@TestPropertySource(properties = {
        "app.scheduling.enabled=true",
        "app.whatsapp.work.startup-sweep-delay-ms=3600000",
        "app.whatsapp.work.sweep-interval-ms=3600000"
})
class WhatsAppWorkTriggerTest extends AbstractWhatsAppIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageService messageService;

    @Test
    void anInboundWebhookIsProcessedWithoutAnyoneCallingTheProcessor() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Trigger Co 1", "Trigger Owner 1",
                "trigger-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String phoneNumberId = connectWhatsApp(owner, "trigger-pn-1");

        postSignedWebhook(inboundPayload(phoneNumberId, "15552220001", "wamid.TRIGGER1", "Is anyone there?"));

        Message message = waitFor(() -> messageRepository.findByTenantIdAndWaMessageId(tenantId, "wamid.TRIGGER1"));
        assertThat(message.getBody()).isEqualTo("Is anyone there?");
    }

    @Test
    void anAgentsReplyIsSentWithoutAnyoneCallingTheSender() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Trigger Co 2", "Trigger Owner 2",
                "trigger-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "trigger-pn-2");
        String conversationId = startConversation(owner, createCustomer(owner, "+14155557001", "Trigger Customer 2"));
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.TRIGGERSEED2", "hi");
        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.success("wamid.LEFTOVER"));
        when(gateway.sendText(any(), eq("+14155557001"), anyString())).thenReturn(SendResult.success("wamid.TRIGGEROUT2"));

        UUID messageId = reply(owner, conversationId, "On it");

        Message sent = waitFor(() -> messageRepository.findById(messageId)
                .filter(m -> m.getStatus() == MessageStatus.SENT));
        assertThat(sent.getWaMessageId()).isEqualTo("wamid.TRIGGEROUT2");
    }

    @Test
    void aFailedSendIsRetriedWhenItsBackoffEndsWithNoSweep() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Trigger Co 3", "Trigger Owner 3",
                "trigger-owner-3@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "trigger-pn-3");
        String conversationId = startConversation(owner, createCustomer(owner, "+14155557002", "Trigger Customer 3"));
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.TRIGGERSEED3", "hi");
        // Fails once (a 4-second backoff), then succeeds. Only the timed
        // wake-up can deliver the second attempt: the sweep is an hour away.
        //
        // Stubbed for this customer's number only. The database is shared
        // with every other test class, and with the scheduler on, their
        // leftover PENDING rows are sent too; a catch-all stub would let one
        // of those consume the failure meant for this message.
        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.success("wamid.LEFTOVER"));
        when(gateway.sendText(any(), eq("+14155557002"), anyString()))
                .thenReturn(SendResult.failure("temporary Meta outage"))
                .thenReturn(SendResult.success("wamid.TRIGGEROUT3"));

        UUID messageId = reply(owner, conversationId, "Retry me");

        Message sent = waitFor(() -> messageRepository.findById(messageId)
                .filter(m -> m.getStatus() == MessageStatus.SENT));
        assertThat(sent.getAttemptCount()).isEqualTo(1);
    }

    private UUID reply(MockHttpSession owner, String conversationId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    private static <T> T waitFor(Supplier<Optional<T>> probe) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Work was not done within 15 seconds of arriving");
    }

    private static String inboundPayload(String phoneNumberId, String fromDigits, String waMessageId, String body) {
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
                            "contacts": [],
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
                """.formatted(phoneNumberId, fromDigits, waMessageId, body);
    }
}
