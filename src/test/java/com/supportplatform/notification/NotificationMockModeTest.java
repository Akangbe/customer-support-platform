package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.whatsapp.MockWhatsAppGateway;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mock mode end to end: a real send request, no Mockito stub anywhere, and
 * nothing leaves the process. This is the mode a tenant integrates against
 * before their templates are approved.
 */
@TestPropertySource(properties = "app.whatsapp.mock=true")
class NotificationMockModeTest extends AbstractApiKeyIntegrationTest {

    @Autowired
    private WhatsAppGateway gateway;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Test
    void mockModeSwapsOutTheMetaGatewayEntirely() {
        assertThat(gateway).isInstanceOf(MockWhatsAppGateway.class);
    }

    @Test
    void aSendSucceedsWithoutCallingMeta() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Mock Co 1", "Mock Owner 1", "mock-owner-1@example.com", "password123");
        connectWhatsApp(owner, "mock-pn-1");
        String key = issueApiKey(owner, "Mock sender");

        MvcResult result = mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559401", "order_shipped")))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("SENT");
        // The synthetic prefix makes a mock-mode send obvious after the fact.
        assertThat(body.get("metaMessageId").asText()).startsWith("wamid.MOCK-");

        NotificationLog logged = notificationLogRepository.findById(UUID.fromString(body.get("notificationId").asText()))
                .orElseThrow();
        assertThat(logged.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(logged.getMetaMessageId()).startsWith("wamid.MOCK-");
    }
}
