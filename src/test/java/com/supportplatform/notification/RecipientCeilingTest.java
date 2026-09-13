package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-recipient velocity ceiling in {@code enforce} mode (V14).
 *
 * <p>The limit is lowered to 3 so the tests state their intent in a few
 * calls rather than eleven. What each one really asserts is how many times
 * {@code gateway.sendTemplate} was reached: that call is the billable
 * event and the buzz on a customer's phone.
 */
@TestPropertySource(properties = {
        "app.notifications.recipient-ceiling.mode=enforce",
        "app.notifications.recipient-ceiling.max-per-window=3",
        "app.notifications.recipient-ceiling.window=PT1H"
})
class RecipientCeilingTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Test
    void sendsUpToTheCeilingAreAccepted() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Ceiling Co 1", "Ceiling Owner 1",
                "ceiling-owner-1@example.com", "password123");
        connectWhatsApp(owner, "ceiling-pn-1");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");

        stubUniqueWamids();

        for (int i = 0; i < 3; i++) {
            send(key, "+14155559501").andExpect(status().isAccepted());
        }

        verify(gateway, times(3)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void theSendPastTheCeilingIsRejectedWithRetryAfterAndNeverReachesMeta() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Ceiling Co 2", "Ceiling Owner 2",
                "ceiling-owner-2@example.com", "password123");
        connectWhatsApp(owner, "ceiling-pn-2");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");

        stubUniqueWamids();

        for (int i = 0; i < 3; i++) {
            send(key, "+14155559502").andExpect(status().isAccepted());
        }

        MvcResult rejected = send(key, "+14155559502")
                .andExpect(status().isTooManyRequests())
                .andReturn();

        // A concrete wait, not a fixed guess: the caller should be told how
        // long until the oldest send ages out of the window.
        String retryAfter = rejected.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive().isLessThanOrEqualTo(3600);

        // The fourth send never became a WhatsApp message.
        verify(gateway, times(3)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aRejectedSendIsNotRecordedAsAFailedDelivery() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Ceiling Co 3", "Ceiling Owner 3",
                "ceiling-owner-3@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "ceiling-pn-3");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");

        stubUniqueWamids();

        for (int i = 0; i < 3; i++) {
            send(key, "+14155559503").andExpect(status().isAccepted());
        }
        send(key, "+14155559503").andExpect(status().isTooManyRequests());

        // A ceiling breach is a rejected request, not a failed delivery. If it
        // wrote a row, every usage and billing figure drawn from this table
        // would count a message that was never sent and never charged for.
        long rows = notificationLogRepository.findAll().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .count();
        assertThat(rows).isEqualTo(3);
    }

    @Test
    void theCeilingIsPerRecipientSoOtherCustomersAreUnaffected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Ceiling Co 4", "Ceiling Owner 4",
                "ceiling-owner-4@example.com", "password123");
        connectWhatsApp(owner, "ceiling-pn-4");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");

        stubUniqueWamids();

        for (int i = 0; i < 3; i++) {
            send(key, "+14155559504").andExpect(status().isAccepted());
        }
        send(key, "+14155559504").andExpect(status().isTooManyRequests());

        // The control case, and the one that matters most: a ceiling that is
        // too broad stops real notifications, which is worse than the problem
        // it was added to solve.
        send(key, "+14155559505").andExpect(status().isAccepted());

        verify(gateway, times(4)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void oneTenantsCeilingDoesNotConstrainAnother() throws Exception {
        MockHttpSession firstOwner = registerTenantAndGetSession("Ceiling Co 5", "Ceiling Owner 5",
                "ceiling-owner-5@example.com", "password123");
        connectWhatsApp(firstOwner, "ceiling-pn-5");
        approveTemplate(firstOwner, "trustpady_notification_utility");
        String firstKey = issueApiKey(firstOwner, "Trustpady production");

        MockHttpSession secondOwner = registerTenantAndGetSession("Ceiling Co 6", "Ceiling Owner 6",
                "ceiling-owner-6@example.com", "password123");
        connectWhatsApp(secondOwner, "ceiling-pn-6");
        approveTemplate(secondOwner, "trustpady_notification_utility");
        String secondKey = issueApiKey(secondOwner, "Trustpady production");

        stubUniqueWamids();

        // Same phone number, two tenants. Counting is tenant-scoped (Rule 3),
        // so one business exhausting its ceiling must not mute another's.
        for (int i = 0; i < 3; i++) {
            send(firstKey, "+14155559506").andExpect(status().isAccepted());
        }
        send(firstKey, "+14155559506").andExpect(status().isTooManyRequests());

        send(secondKey, "+14155559506").andExpect(status().isAccepted());
    }

    /**
     * Meta issues a fresh {@code wamid} for every message it accepts, and
     * {@code uq_notification_log_tenant_meta_message_id} (V12) enforces that
     * on our side. A stub returning one fixed id makes the second send of
     * any test collide on that constraint, so the counter here is not
     * incidental — it is what keeps the fixture faithful to the real gateway.
     */
    private void stubUniqueWamids() {
        AtomicInteger seq = new AtomicInteger();
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.CEIL" + seq.incrementAndGet()));
    }

    private org.springframework.test.web.servlet.ResultActions send(String key, String recipient) throws Exception {
        return mockMvc.perform(post("/api/v1/notifications/send")
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sendRequestBody(recipient, "trustpady_notification_utility")));
    }
}
