package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two businesses sending through one tenant's WhatsApp number, which is how
 * Trustpady's key is set up. Nothing one of them can reach with its API key
 * may reveal or affect the other.
 */
class ClientAccountIsolationTest extends AbstractApiKeyIntegrationTest {

    private static final String TEMPLATE = "trustpady_notification_utility";

    @MockitoBean
    private WhatsAppGateway gateway;

    private final AtomicInteger wamids = new AtomicInteger();

    @Test
    void aClientCannotReadAnotherClientsMessage() throws Exception {
        MockHttpSession owner = sharedTenant("iso-1");
        String clientA = issueApiKey(owner, "Client A");
        String clientB = issueApiKey(owner, "Client B");

        JsonNode sent = send(clientA, "+14155550101", null);
        String notificationId = sent.get("notificationId").asText();
        String metaMessageId = sent.get("metaMessageId").asText();

        mockMvc.perform(get("/api/v1/notifications/" + notificationId).header("Authorization", "Bearer " + clientA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications/" + notificationId).header("Authorization", "Bearer " + clientB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/notifications").param("metaMessageId", metaMessageId)
                        .header("Authorization", "Bearer " + clientB))
                .andExpect(status().isNotFound());
    }

    @Test
    void usageCountsOnlyTheCallersOwnTraffic() throws Exception {
        MockHttpSession owner = sharedTenant("iso-2");
        String clientA = issueApiKey(owner, "Client A");
        String clientB = issueApiKey(owner, "Client B");

        send(clientA, "+14155550201", null);
        send(clientA, "+14155550202", null);
        send(clientB, "+14155550203", null);

        mockMvc.perform(get("/api/v1/notifications/usage").header("Authorization", "Bearer " + clientA))
                .andExpect(jsonPath("$.totalSends").value(2))
                .andExpect(jsonPath("$.distinctRecipients").value(2));
        mockMvc.perform(get("/api/v1/notifications/usage").header("Authorization", "Bearer " + clientB))
                .andExpect(jsonPath("$.totalSends").value(1))
                .andExpect(jsonPath("$.distinctRecipients").value(1));
        // The tenant's Owner still sees everything sent on the number.
        mockMvc.perform(get("/api/v1/notification-usage").session(owner))
                .andExpect(jsonPath("$.totalSends").value(3));
    }

    @Test
    void sharingAnIdempotencyKeyDoesNotSuppressAnotherClientsMessage() throws Exception {
        MockHttpSession owner = sharedTenant("iso-3");
        String clientA = issueApiKey(owner, "Client A");
        String clientB = issueApiKey(owner, "Client B");

        JsonNode fromA = send(clientA, "+14155550301", "order-1");
        JsonNode fromB = send(clientB, "+14155550302", "order-1");

        assertThat(fromB.get("notificationId").asText()).isNotEqualTo(fromA.get("notificationId").asText());
    }

    @Test
    void aRotatedKeyKeepsItsClientsHistory() throws Exception {
        MockHttpSession owner = sharedTenant("iso-4");
        MvcResult created = mockMvc.perform(post("/api/v1/api-keys").session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Client A\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode oldKey = objectMapper.readTree(created.getResponse().getContentAsString());
        String accountId = oldKey.get("key").get("clientAccountId").asText();

        JsonNode sent = send(oldKey.get("apiKey").asText(), "+14155550401", "order-1");

        MvcResult rotated = mockMvc.perform(post("/api/v1/api-keys").session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Client A rotated\",\"clientAccountId\":\"%s\"}".formatted(accountId)))
                .andExpect(status().isCreated())
                .andReturn();
        String newKey = objectMapper.readTree(rotated.getResponse().getContentAsString()).get("apiKey").asText();

        mockMvc.perform(get("/api/v1/notifications/" + sent.get("notificationId").asText())
                        .header("Authorization", "Bearer " + newKey))
                .andExpect(status().isOk());
        // A retry that straddles the rotation is still recognised as a retry.
        mockMvc.perform(sendRequest(newKey, "+14155550401", "order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(sent.get("notificationId").asText()));
    }

    @Test
    void aKeyCannotJoinAnAccountInAnotherTenant() throws Exception {
        MockHttpSession ownerA = sharedTenant("iso-5a");
        MockHttpSession ownerB = sharedTenant("iso-5b");
        MvcResult created = mockMvc.perform(post("/api/v1/api-keys").session(ownerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Client A\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accountId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("key").get("clientAccountId").asText();

        mockMvc.perform(post("/api/v1/api-keys").session(ownerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Intruder\",\"clientAccountId\":\"%s\"}".formatted(accountId)))
                .andExpect(status().isNotFound());
    }

    private MockHttpSession sharedTenant(String suffix) throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Shared Co " + suffix, "Shared Owner",
                suffix + "@example.com", "password123");
        connectWhatsApp(owner, "pn-" + suffix);
        approveTemplate(owner, TEMPLATE);
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.ISO" + wamids.incrementAndGet()));
        return owner;
    }

    private JsonNode send(String apiKey, String recipient, String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(sendRequest(apiKey, recipient, idempotencyKey))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder sendRequest(String apiKey, String recipient, String idempotencyKey) {
        MockHttpServletRequestBuilder request = post("/api/v1/notifications/send")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sendRequestBody(recipient, TEMPLATE));
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }
}
