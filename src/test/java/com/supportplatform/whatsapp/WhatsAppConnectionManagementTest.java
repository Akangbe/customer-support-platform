package com.supportplatform.whatsapp;

import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers whatsapp-domain.md §2: Owner/Admin-only connect (upsert), never echoing the credential back. */
class WhatsAppConnectionManagementTest extends AbstractWhatsAppIntegrationTest {

    @Test
    void ownerCanConnectAndViewWhatsApp() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("WA Co 1", "WA Owner 1", "wa-owner-1@example.com", "password123");

        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"pn-1001","wabaId":"waba-1001","accessToken":"secret-token-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value("pn-1001"))
                .andExpect(jsonPath("$.wabaId").value("waba-1001"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value("pn-1001"));
    }

    @Test
    void reconnectingReplacesTheExistingCredentials() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("WA Co 2", "WA Owner 2", "wa-owner-2@example.com", "password123");

        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"pn-2001","wabaId":"waba-2001","accessToken":"first-token"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"pn-2002","wabaId":"waba-2002","accessToken":"rotated-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value("pn-2002"));

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(owner))
                .andExpect(jsonPath("$.phoneNumberId").value("pn-2002"));
    }

    @Test
    void agentCannotConnectOrViewWhatsApp() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("WA Co 3", "WA Owner 3", "wa-owner-3@example.com", "password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "wa-agent-1@example.com", "WA Agent 1", UserRole.AGENT, "agent-password123");

        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(agent).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"pn-3001","wabaId":"waba-3001","accessToken":"token"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(agent))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotConnectWhatsApp() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("WA Co 4", "WA Owner 4", "wa-owner-4@example.com", "password123");
        MockHttpSession manager = inviteActivateAndLogin(owner, "wa-manager-1@example.com", "WA Manager 1", UserRole.MANAGER, "manager-password123");

        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"pn-4001","wabaId":"waba-4001","accessToken":"token"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanConnectWhatsApp() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("WA Co 5", "WA Owner 5", "wa-owner-5@example.com", "password123");
        MockHttpSession admin = inviteActivateAndLogin(owner, "wa-admin-1@example.com", "WA Admin 1", UserRole.ADMIN, "admin-password123");

        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"pn-5001","wabaId":"waba-5001","accessToken":"token"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void viewingWithNoConnectionReturnsNotFound() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("WA Co 6", "WA Owner 6", "wa-owner-6@example.com", "password123");

        mockMvc.perform(get("/api/v1/whatsapp/connection").session(owner))
                .andExpect(status().isNotFound());
    }
}
