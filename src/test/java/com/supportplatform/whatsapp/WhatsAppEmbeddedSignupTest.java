package com.supportplatform.whatsapp;

import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers whatsapp-domain.md §6: ADR-011 Phase C Embedded Signup onboarding via the gateway boundary. */
class WhatsAppEmbeddedSignupTest extends AbstractWhatsAppIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Test
    void ownerCanCompleteEmbeddedSignup() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("ES Co 1", "ES Owner 1", "es-owner-1@example.com", "password123");
        when(gateway.exchangeCodeForToken("auth-code-1")).thenReturn(OAuthExchangeResult.success("exchanged-token"));
        when(gateway.subscribeToWaba("waba-es-1", "exchanged-token")).thenReturn(true);

        mockMvc.perform(post("/api/v1/whatsapp/connection/embedded-signup")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"auth-code-1","phoneNumberId":"pn-es-1","wabaId":"waba-es-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value("pn-es-1"))
                .andExpect(jsonPath("$.wabaId").value("waba-es-1"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        verify(gateway).subscribeToWaba("waba-es-1", "exchanged-token");
    }

    @Test
    void aFailedCodeExchangeReturnsBadRequestAndPersistsNothing() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("ES Co 2", "ES Owner 2", "es-owner-2@example.com", "password123");
        when(gateway.exchangeCodeForToken("bad-code")).thenReturn(OAuthExchangeResult.failure("code expired"));

        mockMvc.perform(post("/api/v1/whatsapp/connection/embedded-signup")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"bad-code","phoneNumberId":"pn-es-2","wabaId":"waba-es-2"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFailedWebhookSubscriptionStillPersistsTheConnection() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("ES Co 3", "ES Owner 3", "es-owner-3@example.com", "password123");
        when(gateway.exchangeCodeForToken("auth-code-3")).thenReturn(OAuthExchangeResult.success("exchanged-token-3"));
        when(gateway.subscribeToWaba(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/whatsapp/connection/embedded-signup")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"auth-code-3","phoneNumberId":"pn-es-3","wabaId":"waba-es-3"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value("pn-es-3"));
    }

    @Test
    void agentCannotCompleteEmbeddedSignup() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("ES Co 4", "ES Owner 4", "es-owner-4@example.com", "password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "es-agent-1@example.com", "ES Agent 1", UserRole.AGENT, "agent-password123");

        mockMvc.perform(post("/api/v1/whatsapp/connection/embedded-signup")
                        .session(agent).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"auth-code-4","phoneNumberId":"pn-es-4","wabaId":"waba-es-4"}
                                """))
                .andExpect(status().isForbidden());
    }
}
