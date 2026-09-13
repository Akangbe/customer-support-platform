package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Outbound idempotency (V15). Every assertion here is ultimately about how
 * many times {@code gateway.sendTemplate} was reached — that call is the
 * billable event and the buzz on a customer's phone.
 *
 * <p>The scenario is the production one: a caller re-invokes the send for
 * a business event it has already notified, because nothing on its side
 * recorded that the first attempt succeeded.
 */
class NotificationIdempotencyTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Test
    void aRepeatedKeyIsAnsweredFromTheRecordAndNeverReachesMeta() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Idem Co 1", "Idem Owner 1",
                "idem-owner-1@example.com", "password123");
        connectWhatsApp(owner, "idem-pn-1");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");
        stubUniqueWamids();

        MvcResult first = send(key, "+14155559701", "order-8813")
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andReturn();

        MvcResult replay = send(key, "+14155559701", "order-8813")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andReturn();

        verify(gateway, times(1)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());

        // The caller is pointed at the same send both times, so a client that
        // polls the returned id is not left holding an id we invented.
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("notificationId").asText())
                .isEqualTo(firstBody.get("notificationId").asText());
    }

    @Test
    void withoutAKeyNothingIsDeduplicated() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Idem Co 2", "Idem Owner 2",
                "idem-owner-2@example.com", "password123");
        connectWhatsApp(owner, "idem-pn-2");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");
        stubUniqueWamids();

        // The load-bearing test. The template carries no parameters, so these
        // two requests are byte-identical whether they are one event retried
        // or two different events. Inferring a key here would silently drop a
        // real notification, so nothing is inferred: both send.
        send(key, "+14155559702", null).andExpect(status().isAccepted())
                .andExpect(header().string("X-Idempotent-Replay", "false"));
        send(key, "+14155559702", null).andExpect(status().isAccepted())
                .andExpect(header().string("X-Idempotent-Replay", "false"));

        verify(gateway, times(2)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void differentKeysAreDifferentEventsAndBothSend() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Idem Co 3", "Idem Owner 3",
                "idem-owner-3@example.com", "password123");
        connectWhatsApp(owner, "idem-pn-3");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");
        stubUniqueWamids();

        send(key, "+14155559703", "order-1").andExpect(status().isAccepted());
        send(key, "+14155559703", "order-2").andExpect(status().isAccepted());

        verify(gateway, times(2)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aRecordedFailureIsReplayedAsAFailureNotAsSuccess() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Idem Co 4", "Idem Owner 4",
                "idem-owner-4@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "idem-pn-4");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");

        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(SendResult.failure("Meta said no"));

        send(key, "+14155559704", "order-fail").andExpect(status().isBadGateway());

        // Replaying success here would have the caller believe a customer was
        // notified when nobody was. The recorded outcome is replayed as it is.
        send(key, "+14155559704", "order-fail").andExpect(status().isBadGateway());

        verify(gateway, times(1)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());

        List<NotificationLog> rows = notificationLogRepository.findAll().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void concurrentRequestsSharingAKeySendExactlyOnce() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Idem Co 5", "Idem Owner 5",
                "idem-owner-5@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "idem-pn-5");
        approveTemplate(owner, "trustpady_notification_utility");
        String apiKey = issueApiKey(owner, "Trustpady production");
        stubUniqueWamids();

        // The sub-1.5s bursts seen in production: several workers dispatching
        // the same business event at once. The lookup can miss for all of
        // them, so the unique index — not the lookup — is what has to hold.
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        send(apiKey, "+14155559705", "order-concurrent");
                    } catch (Exception ignored) {
                        // Individual outcomes vary by who wins the race; the
                        // assertions below are what matter.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        verify(gateway, times(1)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());

        List<NotificationLog> rows = notificationLogRepository.findAll().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    /**
     * Meta issues a fresh {@code wamid} per accepted message and
     * {@code uq_notification_log_tenant_meta_message_id} (V12) enforces that
     * here, so a stub returning one fixed id would collide on the second
     * send rather than testing what the test names.
     */
    private void stubUniqueWamids() {
        AtomicInteger seq = new AtomicInteger();
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.IDEM" + seq.incrementAndGet()));
    }

    private org.springframework.test.web.servlet.ResultActions send(String apiKey, String recipient,
                                                                      String idempotencyKey) throws Exception {
        var request = post("/api/v1/notifications/send")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sendRequestBody(recipient, "trustpady_notification_utility"));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }
}
