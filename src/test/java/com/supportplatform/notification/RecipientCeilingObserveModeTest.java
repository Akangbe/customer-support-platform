package com.supportplatform.notification;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shipping default. {@code observe} evaluates the ceiling and logs
 * every breach but blocks nothing.
 *
 * <p>This test is the reason the mode exists: it pins the guarantee that
 * deploying the ceiling changes no caller's behaviour until someone
 * deliberately flips the flag. Turning a throttle on against live traffic
 * before confirming what it catches risks silencing a real notification,
 * and an unsent notification is a worse failure than a duplicate one.
 */
@TestPropertySource(properties = {
        "app.notifications.recipient-ceiling.mode=observe",
        "app.notifications.recipient-ceiling.max-per-window=3",
        "app.notifications.recipient-ceiling.window=PT1H"
})
class RecipientCeilingObserveModeTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Test
    void observeModeLetsEverySendThroughEvenWellPastTheCeiling() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Observe Co 1", "Observe Owner 1",
                "observe-owner-1@example.com", "password123");
        connectWhatsApp(owner, "observe-pn-1");
        approveTemplate(owner, "trustpady_notification_utility");
        String key = issueApiKey(owner, "Trustpady production");

        // A fresh id per call, as Meta issues: V12 uniquely indexes
        // (tenant_id, meta_message_id), so a fixed stub id would collide on
        // the second send rather than testing the ceiling.
        AtomicInteger seq = new AtomicInteger();
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.OBS" + seq.incrementAndGet()));

        // Twice the limit, every one accepted.
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/api/v1/notifications/send")
                            .header("Authorization", "Bearer " + key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sendRequestBody("+14155559601", "trustpady_notification_utility")))
                    .andExpect(status().isAccepted());
        }

        verify(gateway, times(6)).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());
    }
}
