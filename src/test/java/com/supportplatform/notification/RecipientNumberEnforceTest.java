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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recipient validation switched to enforce: an impossible number is turned
 * away at the door, before Meta is called and before any row exists, so it
 * is neither sent nor counted as a failed delivery.
 */
@TestPropertySource(properties = "app.notifications.recipient-validation.mode=enforce")
class RecipientNumberEnforceTest extends AbstractApiKeyIntegrationTest {

    private static final String TEMPLATE = "trustpady_notification_utility";

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Test
    void anImpossibleNumberIsRejectedWithoutReachingMetaOrTheLog() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Number Co 1", "Number Owner 1",
                "number-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String key = setUpSending(owner, "number-pn-1");

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+234808084000004", TEMPLATE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("extra or missing digits")));

        verify(gateway, never()).sendTemplate(any(), anyString(), anyString(), anyString(), any(), any());
        assertThat(notificationLogRepository.findAll().stream().filter(n -> n.getTenantId().equals(tenantId)))
                .isEmpty();
    }

    @Test
    void aValidNumberIsStillSent() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Number Co 2", "Number Owner 2",
                "number-owner-2@example.com", "password123");
        String key = setUpSending(owner, "number-pn-2");

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+2349031234173", TEMPLATE)))
                .andExpect(status().isAccepted());
    }

    private String setUpSending(MockHttpSession owner, String phoneNumberId) throws Exception {
        connectWhatsApp(owner, phoneNumberId);
        approveTemplate(owner, TEMPLATE);
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(SendResult.success("wamid.NUMBER" + phoneNumberId));
        return issueApiKey(owner, "Trustpady production");
    }
}
