package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.email.EmailGateway;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The daily summary's shipping default: the tenant's own Owners and Admins
 * see it, the key's contact does not, until the operator flips to "all".
 * No recipients property is set here, so this is the default production
 * gets.
 */
@TestPropertySource(properties = "app.notifications.usage-alert.enabled=false")
class DailySpendSummaryPreviewTest extends AbstractApiKeyIntegrationTest {

    private static final String TEMPLATE = "trustpady_notification_utility";

    @MockitoBean
    private WhatsAppGateway gateway;

    @MockitoBean
    private EmailGateway emailGateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DailySpendSummarizer summarizer;

    @Test
    void byDefaultOnlyTheTenantsOwnPeopleAreTold() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Preview Co 1", "Preview Owner 1",
                "preview-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "preview-pn-1");
        approveTemplate(owner, TEMPLATE);
        AtomicInteger seq = new AtomicInteger();
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.PREVIEW" + seq.incrementAndGet()));

        MvcResult created = mockMvc.perform(post("/api/v1/api-keys")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Trustpady production\",\"contactEmail\":\"billing@trustpady.example\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(created.getResponse().getContentAsString());
        String key = node.get("apiKey").asText();

        send(key, "+14155558600");
        jdbcTemplate.update("UPDATE notification_log SET created_at = created_at - interval '1 day' WHERE tenant_id = ?",
                tenantId);
        summarizer.forgetChecks();
        send(key, "+14155558601");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(emailGateway).send(to.capture(), anyString(), anyString(), anyString());
        assertThat(to.getAllValues()).containsExactly("preview-owner-1@example.com");
    }

    private void send(String apiKey, String recipient) throws Exception {
        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody(recipient, TEMPLATE)))
                .andExpect(status().isAccepted());
    }
}
