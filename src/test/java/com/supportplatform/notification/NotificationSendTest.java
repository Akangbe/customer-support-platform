package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppConnection;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/notifications/send: relaying a template through the tenant's own connection, and logging every attempt. */
class NotificationSendTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Test
    void aSuccessfulSendIsAcceptedAndLogged() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 1", "Send Owner 1", "send-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "send-pn-1");
        String key = issueApiKey(owner, "Sender");

        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(SendResult.success("wamid.SEND1"));

        MvcResult result = mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559301", "order_shipped")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.metaMessageId").value("wamid.SEND1"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        NotificationLog logged = notificationLogRepository.findById(UUID.fromString(body.get("notificationId").asText()))
                .orElseThrow();

        assertThat(logged.getTenantId()).isEqualTo(tenantId);
        assertThat(logged.getRecipient()).isEqualTo("+14155559301");
        assertThat(logged.getTemplateName()).isEqualTo("order_shipped");
        assertThat(logged.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(logged.getMetaMessageId()).isEqualTo("wamid.SEND1");
        assertThat(logged.getApiKeyId()).isNotNull();
    }

    @Test
    void languageCodeDefaultsToEnglishAndBodyParamsAreForwarded() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 2", "Send Owner 2", "send-owner-2@example.com", "password123");
        connectWhatsApp(owner, "send-pn-2");
        String key = issueApiKey(owner, "Sender");

        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(SendResult.success("wamid.SEND2"));

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipient":"+14155559302","templateName":"order_shipped",
                                 "bodyParams":["Ada","ORD-42"],"buttonUrlParam":"track/ORD-42"}
                                """))
                .andExpect(status().isAccepted());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> params = ArgumentCaptor.forClass(List.class);
        verify(gateway).sendTemplate(any(), eq("+14155559302"), eq("order_shipped"), eq("en"),
                params.capture(), eq("track/ORD-42"));
        assertThat(params.getValue()).containsExactly("Ada", "ORD-42");
    }

    @Test
    void anExplicitLanguageCodeIsUsed() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 3", "Send Owner 3", "send-owner-3@example.com", "password123");
        connectWhatsApp(owner, "send-pn-3");
        String key = issueApiKey(owner, "Sender");

        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(SendResult.success("wamid.SEND3"));

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipient":"+14155559303","templateName":"order_shipped","languageCode":"fr"}
                                """))
                .andExpect(status().isAccepted());

        verify(gateway).sendTemplate(any(), eq("+14155559303"), eq("order_shipped"), eq("fr"), any(), isNull());
    }

    @Test
    void aMetaRejectionIsACleanErrorAndStillLogged() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 4", "Send Owner 4", "send-owner-4@example.com", "password123");
        connectWhatsApp(owner, "send-pn-4");
        String key = issueApiKey(owner, "Sender");

        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(SendResult.failure("(#132001) Template name does not exist in the translation"));

        MvcResult result = mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559304", "no_such_template")))
                .andExpect(status().isBadGateway())
                .andReturn();

        String message = objectMapper.readTree(result.getResponse().getContentAsString()).get("message").asText();
        // Meta's own error text stays on our side; the caller gets a reference id.
        assertThat(message).doesNotContain("132001").contains("Reference:");

        UUID tenantId = extractTenantId(owner);
        NotificationLog logged = notificationLogRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, 1))
                .getContent().getFirst();

        // The failure survived the thrown exception — the log write is its own transaction.
        assertThat(logged.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(logged.getFailureReason()).contains("132001");
        assertThat(logged.getMetaMessageId()).isNull();
    }

    @Test
    void sendingWithoutAConnectedNumberIsAConflict() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 5", "Send Owner 5", "send-owner-5@example.com", "password123");
        String key = issueApiKey(owner, "Sender");

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559305", "order_shipped")))
                .andExpect(status().isConflict());
    }

    @Test
    void aNonE164RecipientIsRejectedBeforeAnyMetaCall() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 6", "Send Owner 6", "send-owner-6@example.com", "password123");
        connectWhatsApp(owner, "send-pn-6");
        String key = issueApiKey(owner, "Sender");

        for (String bad : List.of("14155559306", "+0415555930", "+1-415-555-9306", "not-a-number")) {
            mockMvc.perform(post("/api/v1/notifications/send")
                            .header("Authorization", "Bearer " + key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sendRequestBody(bad, "order_shipped")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("recipient"));
        }

        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void aBlankTemplateNameIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 7", "Send Owner 7", "send-owner-7@example.com", "password123");
        connectWhatsApp(owner, "send-pn-7");
        String key = issueApiKey(owner, "Sender");

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559307", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("templateName"));
    }

    @Test
    void theSendUsesTheKeysOwnTenantConnectionAndIgnoresAnyTenantIdInTheBody() throws Exception {
        MockHttpSession ownerA = registerTenantAndGetSession("Send Co 8a", "Send Owner 8a", "send-owner-8a@example.com", "password123");
        MockHttpSession ownerB = registerTenantAndGetSession("Send Co 8b", "Send Owner 8b", "send-owner-8b@example.com", "password123");
        connectWhatsApp(ownerA, "send-pn-8a");
        connectWhatsApp(ownerB, "send-pn-8b");

        UUID tenantA = extractTenantId(ownerA);
        UUID tenantB = extractTenantId(ownerB);
        String keyA = issueApiKey(ownerA, "Tenant A sender");

        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(SendResult.success("wamid.SEND8"));

        // The body names tenant B. It is an unknown property to the DTO and,
        // more importantly, there is no code path that could read it (Rule 3).
        MvcResult result = mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + keyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipient":"+14155559308","templateName":"order_shipped","tenantId":"%s"}
                                """.formatted(tenantB)))
                .andExpect(status().isAccepted())
                .andReturn();

        ArgumentCaptor<WhatsAppConnection> connection = ArgumentCaptor.forClass(WhatsAppConnection.class);
        verify(gateway).sendTemplate(connection.capture(), anyString(), anyString(), anyString(), any(), isNull());

        // A's number, A's token — B's connection was never reachable from this request.
        assertThat(connection.getValue().getTenantId()).isEqualTo(tenantA);
        assertThat(connection.getValue().getPhoneNumberId()).isEqualTo("send-pn-8a");

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        NotificationLog logged = notificationLogRepository.findById(UUID.fromString(body.get("notificationId").asText()))
                .orElseThrow();
        assertThat(logged.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void theResponseNeverCarriesTheMetaToken() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Send Co 9", "Send Owner 9", "send-owner-9@example.com", "password123");
        connectWhatsApp(owner, "send-pn-9");
        String key = issueApiKey(owner, "Sender");

        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(SendResult.success("wamid.SEND9"));

        MvcResult result = mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559309", "order_shipped")))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("tenant-access-token")
                .doesNotContain("send-pn-9");
    }
}
