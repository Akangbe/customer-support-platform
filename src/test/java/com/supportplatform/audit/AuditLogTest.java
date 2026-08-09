package com.supportplatform.audit;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers audit-domain.md §1: every FR-AUD-001–003 publish site produces a readable, correctly-attributed entry. */
class AuditLogTest extends AbstractIntegrationTest {

    @Test
    void invitingAUserIsAudited() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Audit Co 1", "Audit Owner 1", "audit-owner-1@example.com", "password123");
        String ownerId = extractUserId(owner);

        inviteActivateAndLogin(owner, "audit-agent-1@example.com", "Audit Agent 1", UserRole.AGENT, "agent-password123");

        mockMvc.perform(get("/api/v1/audit-log").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("USER_INVITED"))
                .andExpect(jsonPath("$.content[0].actorUserId").value(ownerId))
                .andExpect(jsonPath("$.content[0].detail").value("Invited audit-agent-1@example.com as AGENT"));
    }

    @Test
    void changingARoleIsAudited() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Audit Co 2", "Audit Owner 2", "audit-owner-2@example.com", "password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "audit-agent-2@example.com", "Audit Agent 2", UserRole.AGENT, "agent-password123");
        String agentId = extractUserId(agent);

        mockMvc.perform(patch("/api/v1/users/" + agentId + "/role")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-log").session(owner))
                .andExpect(jsonPath("$.content[0].action").value("USER_ROLE_CHANGED"))
                .andExpect(jsonPath("$.content[0].detail").value("Changed role from AGENT to MANAGER"));
    }

    @Test
    void disablingAndEnablingAUserIsAudited() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Audit Co 3", "Audit Owner 3", "audit-owner-3@example.com", "password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "audit-agent-3@example.com", "Audit Agent 3", UserRole.AGENT, "agent-password123");
        String agentId = extractUserId(agent);

        mockMvc.perform(post("/api/v1/users/" + agentId + "/disable").session(owner))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users/" + agentId + "/enable").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-log").session(owner))
                .andExpect(jsonPath("$.content[0].action").value("USER_ENABLED"))
                .andExpect(jsonPath("$.content[1].action").value("USER_DISABLED"));
    }

    @Test
    void assigningAndUnassigningAConversationIsAudited() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Audit Co 4", "Audit Owner 4", "audit-owner-4@example.com", "password123");
        String ownerId = extractUserId(owner);
        String customerId = createCustomer(owner, "+14155556001", "Audit Customer 1");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + ownerId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/unassign").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-log").session(owner))
                .andExpect(jsonPath("$.content[0].action").value("CONVERSATION_UNASSIGNED"))
                .andExpect(jsonPath("$.content[0].targetId").value(conversationId))
                .andExpect(jsonPath("$.content[1].action").value("CONVERSATION_ASSIGNED"));
    }

    @Test
    void connectingWhatsAppIsAuditedWithoutLeakingTheAccessToken() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Audit Co 5", "Audit Owner 5", "audit-owner-5@example.com", "password123");

        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"audit-pn-1","wabaId":"audit-waba-1","accessToken":"super-secret-token"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-log").session(owner))
                .andExpect(jsonPath("$.content[0].action").value("WHATSAPP_CONNECTED"))
                .andExpect(jsonPath("$.content[0].detail").value("Connected WhatsApp (phone_number_id=audit-pn-1)"))
                .andExpect(jsonPath("$.content[0].detail", not(containsString("super-secret-token"))));
    }
}
