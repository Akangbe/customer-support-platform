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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The daily spend summary (V18).
 *
 * <p>"Yesterday" is made by moving a test's own rows back a day in the
 * database, since a test cannot wait for midnight. The volume alert is
 * switched off so its mails cannot be mistaken for a summary.
 */
@TestPropertySource(properties = {
        "app.notifications.usage-alert.enabled=false",
        "app.notifications.daily-summary.enabled=true",
        "app.notifications.daily-summary.price-per-delivered=0.01",
        "app.notifications.daily-summary.currency=AUD",
        "app.notifications.daily-summary.recipients=all"
})
class DailySpendSummaryTest extends AbstractApiKeyIntegrationTest {

    private static final String TEMPLATE = "trustpady_notification_utility";

    @MockitoBean
    private WhatsAppGateway gateway;

    @MockitoBean
    private EmailGateway emailGateway;

    @Autowired
    private NotificationDailySummaryRepository summaryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DailySpendSummarizer summarizer;

    private final AtomicInteger seq = new AtomicInteger();

    @Test
    void theFirstSendOfADaySummarizesYesterdayForTheKeysContact() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Summary Co 1", "Summary Owner 1",
                "summary-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "summary-pn-1", "billing@trustpady.example");

        // Yesterday: three delivered (one of them read), one failed, one
        // with no receipt yet. Only the three are charged.
        for (int i = 0; i < 5; i++) {
            send(key, "+1415555800" + i);
        }
        moveToYesterday(tenantId);
        setStatus(tenantId, "+14155558000", "DELIVERED");
        setStatus(tenantId, "+14155558001", "DELIVERED");
        setStatus(tenantId, "+14155558002", "READ");
        setStatus(tenantId, "+14155558003", "FAILED");

        send(key, "+14155558009");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(2)).send(to.capture(), subject.capture(), anyString(), text.capture());

        assertThat(to.getAllValues()).containsExactly("summary-owner-1@example.com", "billing@trustpady.example");
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        assertThat(subject.getValue()).isEqualTo("WhatsApp notifications on " + yesterday + ": 3 delivered, AUD 0.03");
        assertThat(text.getValue())
                .contains("Messages sent: 5")
                .contains("Delivered (charged): 3")
                .contains("Failed (not charged): 1")
                .contains("Awaiting delivery confirmation: 1")
                .contains("Estimated cost: AUD 0.03 (3 delivered x AUD 0.01)");

        List<NotificationDailySummary> ledger = summariesFor(tenantId);
        assertThat(ledger).hasSize(1);
        NotificationDailySummary summary = ledger.get(0);
        assertThat(summary.getPeriodStart()).isEqualTo(yesterday);
        assertThat(summary.getSends()).isEqualTo(5);
        assertThat(summary.getDelivered()).isEqualTo(3);
        assertThat(summary.getFailed()).isEqualTo(1);
        assertThat(summary.getCost()).isEqualByComparingTo(new BigDecimal("0.03"));
    }

    @Test
    void aDayIsSummarizedOnceNoMatterHowManySendsFollow() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Summary Co 2", "Summary Owner 2",
                "summary-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "summary-pn-2", null);

        send(key, "+14155558100");
        moveToYesterday(tenantId);

        for (int i = 1; i < 5; i++) {
            send(key, "+1415555810" + i);
        }

        verify(emailGateway, times(1)).send(anyString(), anyString(), anyString(), anyString());
        assertThat(summariesFor(tenantId)).hasSize(1);
    }

    @Test
    void theLedgerRowIsWhatSuppressesTheRepeat() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Summary Co 3", "Summary Owner 3",
                "summary-owner-3@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "summary-pn-3", null);

        send(key, "+14155558200");
        moveToYesterday(tenantId);
        UUID apiKeyId = apiKeyIdOf(tenantId);

        // Pre-claim yesterday, exactly as a previous run of the process
        // would have. The in-memory shortcut is fresh for this key, so only
        // the durable row can stop the mail.
        summaryRepository.save(new NotificationDailySummary(new DailySpendSummaryEvent(tenantId, apiKeyId,
                LocalDate.now(ZoneOffset.UTC).minusDays(1), 1, 0, 0,
                new BigDecimal("0.01"), BigDecimal.ZERO, "AUD")));

        send(key, "+14155558201");

        verify(emailGateway, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aKeyIsToldOnlyAboutItsOwnTraffic() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Summary Co 4", "Summary Owner 4",
                "summary-owner-4@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String trustpady = setUpSending(owner, "summary-pn-4", "billing@trustpady.example");
        String other = issueKey(owner, "billing@other.example");

        // Two integrators on one tenant, as Trustpady is on the operator's.
        send(trustpady, "+14155558300");
        for (int i = 1; i < 4; i++) {
            send(other, "+1415555830" + i);
        }
        moveToYesterday(tenantId);

        send(trustpady, "+14155558309");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailGateway).send(eq("billing@trustpady.example"), anyString(), anyString(), text.capture());
        assertThat(text.getValue()).contains("Messages sent: 1");
        verify(emailGateway, never()).send(eq("billing@other.example"), anyString(), anyString(), anyString());
    }

    @Test
    void todayIsNeverSummarizedWhileItIsStillGoing() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Summary Co 5", "Summary Owner 5",
                "summary-owner-5@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "summary-pn-5", null);

        for (int i = 0; i < 3; i++) {
            send(key, "+1415555840" + i);
        }

        verify(emailGateway, never()).send(anyString(), anyString(), anyString(), anyString());
        assertThat(summariesFor(tenantId)).isEmpty();
    }

    @Test
    void anUnreachableMailboxNeverFailsTheSend() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Summary Co 6", "Summary Owner 6",
                "summary-owner-6@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "summary-pn-6", null);

        send(key, "+14155558500");
        moveToYesterday(tenantId);

        doThrow(new IllegalStateException("SES is not configured"))
                .when(emailGateway).send(anyString(), anyString(), anyString(), anyString());

        // Must still be accepted: a report that can fail a send turns a
        // reporting problem into an outage.
        send(key, "+14155558501");
    }

    private String setUpSending(MockHttpSession owner, String phoneNumberId, String contactEmail) throws Exception {
        connectWhatsApp(owner, phoneNumberId);
        approveTemplate(owner, TEMPLATE);
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.SUMMARY" + seq.incrementAndGet()));
        return issueKey(owner, contactEmail);
    }

    private String issueKey(MockHttpSession owner, String contactEmail) throws Exception {
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
                        .content(sendRequestBody(recipient, TEMPLATE)))
                .andExpect(status().isAccepted());
    }

    /**
     * Moves this tenant's sends into yesterday, and has the summarizer
     * forget that it already checked today — as the process would after a
     * cold start, or on the first send after a real midnight.
     */
    private void moveToYesterday(UUID tenantId) {
        jdbcTemplate.update("UPDATE notification_log SET created_at = created_at - interval '1 day' WHERE tenant_id = ?",
                tenantId);
        summarizer.forgetChecks();
    }

    private void setStatus(UUID tenantId, String recipient, String status) {
        jdbcTemplate.update("UPDATE notification_log SET status = ? WHERE tenant_id = ? AND recipient = ?",
                status, tenantId, recipient);
    }

    private UUID apiKeyIdOf(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT DISTINCT api_key_id FROM notification_log WHERE tenant_id = ?",
                UUID.class, tenantId);
    }

    private List<NotificationDailySummary> summariesFor(UUID tenantId) {
        return summaryRepository.findAll().stream()
                .filter(s -> s.getTenantId().equals(tenantId))
                .toList();
    }
}
