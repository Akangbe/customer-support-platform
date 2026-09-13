package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.email.EmailGateway;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Daily send-volume alerting (V16).
 *
 * <p>Thresholds are lowered to 3 and 6 so a test states its intent in a
 * handful of sends. The behaviour under test is not the arithmetic — it is
 * that the alert happens exactly once per threshold per day, reaches the
 * right people, and can never fail the send that triggered it.
 */
@TestPropertySource(properties = {
        "app.notifications.usage-alert.enabled=true",
        "app.notifications.usage-alert.thresholds=3,6"
})
class DailyUsageAlertTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @MockitoBean
    private EmailGateway emailGateway;

    @Autowired
    private NotificationUsageAlertRepository alertRepository;

    @Test
    void crossingAThresholdAlertsTheOwnerExactlyOnce() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Alert Co 1", "Alert Owner 1",
                "alert-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "alert-pn-1", null);

        // Five sends against a threshold of 3: the alert fires as the third
        // lands and must not fire again on the fourth or fifth.
        for (int i = 0; i < 5; i++) {
            send(key, "+1415555990" + i);
        }

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, atLeastOnce()).send(to.capture(), anyString(), anyString(), anyString());
        assertThat(to.getAllValues()).containsExactly("alert-owner-1@example.com");

        List<NotificationUsageAlert> ledger = alertRepository.findAll().stream()
                .filter(a -> a.getTenantId().equals(tenantId))
                .toList();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getThreshold()).isEqualTo(3);
        assertThat(ledger.get(0).getPeriodStart()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void theLedgerRowIsWhatSuppressesTheRepeat() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Alert Co 2", "Alert Owner 2",
                "alert-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "alert-pn-2", null);

        // Pre-claim today's threshold, exactly as a previous run of the
        // process would have. Suppression must survive a restart, which an
        // in-memory cooldown would not: this service cold starts several
        // times a day and would otherwise re-announce on every wake.
        alertRepository.save(new NotificationUsageAlert(tenantId, LocalDate.now(ZoneOffset.UTC), 3, 3));

        for (int i = 0; i < 4; i++) {
            send(key, "+1415555991" + i);
        }

        verifyNoInteractions(emailGateway);
    }

    @Test
    void theKeysContactIsToldAlongsideTheTenantsOwners() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Alert Co 3", "Alert Owner 3",
                "alert-owner-3@example.com", "password123");
        String key = setUpSending(owner, "alert-pn-3", "integrator@partner.example");

        for (int i = 0; i < 3; i++) {
            send(key, "+1415555992" + i);
        }

        // The traffic is usually the integrator's, so telling them the same
        // morning beats forwarding an email by hand.
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, atLeastOnce()).send(to.capture(), anyString(), anyString(), anyString());
        assertThat(to.getAllValues())
                .containsExactly("alert-owner-3@example.com", "integrator@partner.example");
    }

    @Test
    void theHigherThresholdAlertsSeparately() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Alert Co 4", "Alert Owner 4",
                "alert-owner-4@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "alert-pn-4", null);

        for (int i = 0; i < 7; i++) {
            send(key, "+141555599" + (20 + i));
        }

        // 3 and 6 are separate events: escalation is the point, so crossing
        // the louder one must not be swallowed by having crossed the quieter.
        List<Integer> thresholds = alertRepository.findAll().stream()
                .filter(a -> a.getTenantId().equals(tenantId))
                .map(NotificationUsageAlert::getThreshold)
                .sorted()
                .toList();
        assertThat(thresholds).containsExactly(3, 6);
    }

    @Test
    void anUnreachableMailboxNeverFailsTheSend() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Alert Co 5", "Alert Owner 5",
                "alert-owner-5@example.com", "password123");
        String key = setUpSending(owner, "alert-pn-5", null);

        doThrow(new IllegalStateException("SES is not configured"))
                .when(emailGateway).send(anyString(), anyString(), anyString(), anyString());

        // Monitoring that can fail a send turns an observability problem into
        // an outage. Every one of these must still be accepted.
        for (int i = 0; i < 4; i++) {
            send(key, "+141555599" + (30 + i));
        }
    }

    private String setUpSending(MockHttpSession owner, String phoneNumberId, String contactEmail) throws Exception {
        connectWhatsApp(owner, phoneNumberId);
        approveTemplate(owner, "trustpady_notification_utility");

        AtomicInteger seq = new AtomicInteger();
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.ALERT" + phoneNumberId + seq.incrementAndGet()));

        String body = contactEmail == null
                ? "{\"name\":\"Trustpady production\"}"
                : "{\"name\":\"Trustpady production\",\"contactEmail\":\"%s\"}".formatted(contactEmail);
        MvcResult result = mockMvc.perform(post("/api/v1/api-keys")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("apiKey").asText();
    }

    private void send(String apiKey, String recipient) throws Exception {
        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody(recipient, "trustpady_notification_utility")))
                .andExpect(status().isAccepted());
    }
}
