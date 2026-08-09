package com.supportplatform.whatsapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers whatsapp-domain.md §3: the verification handshake and signature-gated ingestion. */
class WhatsAppWebhookTest extends AbstractWhatsAppIntegrationTest {

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Test
    void verificationHandshakeSucceedsWithTheCorrectToken() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "local-dev-verify-token")
                        .param("hub.challenge", "challenge-123"))
                .andExpect(status().isOk())
                .andExpect(content().string("challenge-123"));
    }

    @Test
    void verificationHandshakeFailsWithTheWrongToken() throws Exception {
        mockMvc.perform(get("/api/v1/whatsapp/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong-token")
                        .param("hub.challenge", "challenge-123"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aValidlySignedDeliveryIsAcceptedAndPersisted() throws Exception {
        long before = webhookEventRepository.count();
        String payload = """
                {"object":"whatsapp_business_account","entry":[]}
                """;

        postSignedWebhook(payload).getResponse();

        assertThat(webhookEventRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void aDeliveryWithAnInvalidSignatureIsRejectedAndNotPersisted() throws Exception {
        long before = webhookEventRepository.count();
        String payload = """
                {"object":"whatsapp_business_account","entry":[]}
                """;

        mockMvc.perform(post("/api/v1/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .content(payload))
                .andExpect(status().isForbidden());

        assertThat(webhookEventRepository.count()).isEqualTo(before);
    }

    @Test
    void aDeliveryWithNoSignatureIsRejected() throws Exception {
        String payload = """
                {"object":"whatsapp_business_account","entry":[]}
                """;

        mockMvc.perform(post("/api/v1/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }
}
