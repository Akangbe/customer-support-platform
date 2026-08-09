package com.supportplatform.audit;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers audit-domain.md §6: reading the audit log is Owner/Admin only. */
class AuditLogAuthorizationTest extends AbstractIntegrationTest {

    @Test
    void ownerAndAdminCanReadTheAuditLog() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Audit Authz Co 1", "Owner", "audit-authz-owner-1@example.com", "password123");
        MockHttpSession admin = inviteActivateAndLogin(owner, "audit-authz-admin-1@example.com", "Admin", UserRole.ADMIN, "admin-password123");

        mockMvc.perform(get("/api/v1/audit-log").session(owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit-log").session(admin)).andExpect(status().isOk());
    }

    @Test
    void managerAndAgentCannotReadTheAuditLog() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Audit Authz Co 2", "Owner", "audit-authz-owner-2@example.com", "password123");
        MockHttpSession manager = inviteActivateAndLogin(owner, "audit-authz-manager-1@example.com", "Manager", UserRole.MANAGER, "manager-password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "audit-authz-agent-1@example.com", "Agent", UserRole.AGENT, "agent-password123");

        mockMvc.perform(get("/api/v1/audit-log").session(manager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit-log").session(agent)).andExpect(status().isForbidden());
    }
}
