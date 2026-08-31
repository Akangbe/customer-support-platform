package com.supportplatform.apikey;

import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The API key filter on /api/v1/notifications/**: what it accepts, what it rejects, and what it leaks. */
class ApiKeyAuthenticationTest extends AbstractApiKeyIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @BeforeEach
    void stubGateway() {
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(SendResult.success("wamid.AUTH1"));
    }

    @Test
    void aValidKeyAuthenticatesAndResolvesItsTenant() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Auth Co 1", "Auth Owner 1", "auth-owner-1@example.com", "password123");
        connectWhatsApp(owner, "auth-pn-1");
        String key = issueApiKey(owner, "Valid key");

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559101", "order_shipped")))
                .andExpect(status().isAccepted());
    }

    @Test
    void theXApiKeyHeaderIsAcceptedToo() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Auth Co 2", "Auth Owner 2", "auth-owner-2@example.com", "password123");
        connectWhatsApp(owner, "auth-pn-2");
        String key = issueApiKey(owner, "Header key");

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559102", "order_shipped")))
                .andExpect(status().isAccepted());
    }

    @Test
    void noKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559103", "order_shipped")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionCookieIsNotAcceptedOnTheMachineApi() throws Exception {
        // The two chains are separate on purpose: a logged-in dashboard user
        // is not a machine caller, and must still present a key here.
        MockHttpSession owner = registerTenantAndGetSession("Auth Co 3", "Auth Owner 3", "auth-owner-3@example.com", "password123");
        connectWhatsApp(owner, "auth-pn-3");

        mockMvc.perform(post("/api/v1/notifications/send")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559104", "order_shipped")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedKeyIsRejectedWithTheSameMessageAsAnUnknownOne() throws Exception {
        String malformed = performUnauthorizedSend("not-even-close");
        String unknownKeyId = performUnauthorizedSend("rd_live_deadbeefdeadbeef.some-secret-value");

        // Identical wording: a caller must not be able to tell a real key_id
        // from a made-up one and enumerate them.
        assertThat(malformed).isEqualTo(unknownKeyId);
    }

    @Test
    void aWrongSecretOnARealKeyIdIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Auth Co 4", "Auth Owner 4", "auth-owner-4@example.com", "password123");
        connectWhatsApp(owner, "auth-pn-4");
        String key = issueApiKey(owner, "Real key");
        String keyId = key.substring("rd_live_".length(), key.indexOf('.'));

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer rd_live_" + keyId + ".wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559105", "order_shipped")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedKeyStopsWorkingImmediately() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Auth Co 5", "Auth Owner 5", "auth-owner-5@example.com", "password123");
        connectWhatsApp(owner, "auth-pn-5");
        String key = issueApiKey(owner, "Doomed key");
        String keyId = key.substring("rd_live_".length(), key.indexOf('.'));
        UUID id = apiKeyRepository.findByKeyId(keyId).orElseThrow().getId();

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559106", "order_shipped")))
                .andExpect(status().isAccepted());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/api-keys/" + id).session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559107", "order_shipped")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usingAKeyStampsLastUsedAt() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Auth Co 6", "Auth Owner 6", "auth-owner-6@example.com", "password123");
        connectWhatsApp(owner, "auth-pn-6");
        String key = issueApiKey(owner, "Tracked key");
        String keyId = key.substring("rd_live_".length(), key.indexOf('.'));

        assertThat(apiKeyRepository.findByKeyId(keyId).orElseThrow().getLastUsedAt()).isNull();

        mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody("+14155559108", "order_shipped")))
                .andExpect(status().isAccepted());

        assertThat(apiKeyRepository.findByKeyId(keyId).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void theExistingDashboardApiStillUsesSessionsAndIsUntouched() throws Exception {
        // Guards the regression the second filter chain could have caused:
        // API key auth must not have been placed in front of everything.
        MockHttpSession owner = registerTenantAndGetSession("Auth Co 7", "Auth Owner 7", "auth-owner-7@example.com", "password123");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/users/me").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("auth-owner-7@example.com"));
    }

    private String performUnauthorizedSend(String presentedKey) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/notifications/send")
                                .header("Authorization", "Bearer " + presentedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sendRequestBody("+14155559109", "order_shipped")))
                        .andExpect(status().isUnauthorized())
                        .andReturn().getResponse().getContentAsString())
                .get("message").asText();
    }
}
