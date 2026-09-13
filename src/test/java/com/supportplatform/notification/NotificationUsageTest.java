package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.user.UserRole;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The usage view, read two ways: by the integrator through its API key,
 * and by an Owner through the dashboard.
 *
 * <p>The pair of figures that matters is {@code totalSends} against
 * {@code distinctRecipients}. Four messages to two people is the shape of
 * the production problem, so the tests assert both rather than a total
 * that would look identical either way.
 */
class NotificationUsageTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Test
    void usageCountsSendsAndPeopleSeparately() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Usage Co 1", "Usage Owner 1",
                "usage-owner-1@example.com", "password123");
        connectWhatsApp(owner, "usage-pn-1");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");
        stubUniqueWamids();

        // Four sends, two people — the shape of the real incident.
        send(key, "+14155559801");
        send(key, "+14155559801");
        send(key, "+14155559801");
        send(key, "+14155559802");

        mockMvc.perform(get("/api/v1/notifications/usage").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSends").value(4))
                .andExpect(jsonPath("$.distinctRecipients").value(2))
                .andExpect(jsonPath("$.byStatus.sent").value(4))
                .andExpect(jsonPath("$.daily.length()").value(1));
    }

    @Test
    void theDashboardSeesTheSameNumbersAsTheIntegrator() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Usage Co 2", "Usage Owner 2",
                "usage-owner-2@example.com", "password123");
        connectWhatsApp(owner, "usage-pn-2");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");
        stubUniqueWamids();

        send(key, "+14155559803");
        send(key, "+14155559804");

        // Session-authenticated, and deliberately on a path outside
        // /api/v1/notifications so a human never needs a machine credential
        // to read their own numbers.
        mockMvc.perform(get("/api/v1/notification-usage").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSends").value(2))
                .andExpect(jsonPath("$.distinctRecipients").value(2));
    }

    @Test
    void oneTenantsUsageNeverIncludesAnothers() throws Exception {
        MockHttpSession firstOwner = registerTenantAndGetSession("Usage Co 3", "Usage Owner 3",
                "usage-owner-3@example.com", "password123");
        connectWhatsApp(firstOwner, "usage-pn-3");
        approveTemplate(firstOwner, "trustpady_notification_utility");
        String firstKey = issueApiKey(firstOwner, "Trustpady production");

        MockHttpSession secondOwner = registerTenantAndGetSession("Usage Co 4", "Usage Owner 4",
                "usage-owner-4@example.com", "password123");
        connectWhatsApp(secondOwner, "usage-pn-4");
        approveTemplate(secondOwner, "trustpady_notification_utility");
        String secondKey = issueApiKey(secondOwner, "Trustpady production");
        stubUniqueWamids();

        send(firstKey, "+14155559805");
        send(firstKey, "+14155559806");
        send(secondKey, "+14155559807");

        // Rule 3: the tenant comes off the key, never off a parameter, so
        // there is nothing a caller could pass to see someone else's volume.
        mockMvc.perform(get("/api/v1/notifications/usage").header("Authorization", "Bearer " + firstKey))
                .andExpect(jsonPath("$.totalSends").value(2));
        mockMvc.perform(get("/api/v1/notifications/usage").header("Authorization", "Bearer " + secondKey))
                .andExpect(jsonPath("$.totalSends").value(1));
    }

    @Test
    void anAgentCannotReadTheTenantsUsage() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Usage Co 5", "Usage Owner 5",
                "usage-owner-5@example.com", "password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "usage-agent-5@example.com", "Usage Agent 5",
                UserRole.AGENT, "password123");

        // Usage is what an API key does, and only Owner/Admin may manage
        // those keys or reach for the kill switch. The same people, so the
        // same authority.
        mockMvc.perform(get("/api/v1/notification-usage").session(agent))
                .andExpect(status().isForbidden());
    }

    @Test
    void anImpossibleRangeIsRejectedRatherThanScanned() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Usage Co 6", "Usage Owner 6",
                "usage-owner-6@example.com", "password123");

        mockMvc.perform(get("/api/v1/notification-usage")
                        .session(owner)
                        .param("from", "2026-09-12")
                        .param("to", "2026-09-05"))
                .andExpect(status().isBadRequest());

        // A mistyped year must not turn into a full-table scan.
        mockMvc.perform(get("/api/v1/notification-usage")
                        .session(owner)
                        .param("from", "2020-01-01")
                        .param("to", "2026-09-12"))
                .andExpect(status().isBadRequest());
    }

    private void stubUniqueWamids() {
        AtomicInteger seq = new AtomicInteger();
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.USE" + seq.incrementAndGet()));
    }

    private void send(String apiKey, String recipient) throws Exception {
        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody(recipient, "trustpady_notification_utility")))
                .andExpect(status().isAccepted());
    }
}
