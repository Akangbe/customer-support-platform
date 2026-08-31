package com.supportplatform.apikey;

import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-key throttling on the notification API. Runs on the default in-memory limiter, so it needs no Redis. */
class RateLimitTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @BeforeEach
    void stubGateway() {
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(SendResult.success("wamid.RATE1"));
    }

    @Test
    void exceedingTheKeysLimitReturns429WithRetryAfter() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Rate Co 1", "Rate Owner 1", "rate-owner-1@example.com", "password123");
        connectWhatsApp(owner, "rate-pn-1");
        String key = issueApiKey(owner, "Tight limit", 2);

        for (int i = 0; i < 2; i++) {
            send(key, "+1415555920" + i).andExpect(status().isAccepted());
        }

        MvcResult throttled = send(key, "+14155559209")
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(throttled.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(objectMapper.readTree(throttled.getResponse().getContentAsString()).get("message").asText())
                .contains("Rate limit exceeded");
    }

    @Test
    void theLimitIsPerKeyNotGlobal() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Rate Co 2", "Rate Owner 2", "rate-owner-2@example.com", "password123");
        connectWhatsApp(owner, "rate-pn-2");
        String spentKey = issueApiKey(owner, "Spent", 1);
        String freshKey = issueApiKey(owner, "Fresh", 1);

        send(spentKey, "+14155559210").andExpect(status().isAccepted());
        send(spentKey, "+14155559211").andExpect(status().isTooManyRequests());

        // Same tenant, different key: its own budget is untouched.
        send(freshKey, "+14155559212").andExpect(status().isAccepted());
    }

    @Test
    void aThrottledRequestNeverReachesTheGateway() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Rate Co 3", "Rate Owner 3", "rate-owner-3@example.com", "password123");
        connectWhatsApp(owner, "rate-pn-3");
        String key = issueApiKey(owner, "One shot", 1);

        send(key, "+14155559213").andExpect(status().isAccepted());
        send(key, "+14155559214").andExpect(status().isTooManyRequests());

        // Exactly one send got through — the 429 was refused before any Meta call.
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1))
                .sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull());
    }

    private org.springframework.test.web.servlet.ResultActions send(String key, String recipient) throws Exception {
        return mockMvc.perform(post("/api/v1/notifications/send")
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sendRequestBody(recipient, "order_shipped")));
    }
}
