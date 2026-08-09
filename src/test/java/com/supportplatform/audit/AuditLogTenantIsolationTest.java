package com.supportplatform.audit;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves Rule 3 for the audit log, same as every other phase's isolation test. */
class AuditLogTenantIsolationTest extends AbstractIntegrationTest {

    @Test
    void aTenantsAuditLogNeverIncludesAnotherTenantsActions() throws Exception {
        MockHttpSession ownerA = registerTenantAndGetSession("Audit Iso Tenant A", "Owner A", "audit-iso-a@example.com", "password123");
        MockHttpSession ownerB = registerTenantAndGetSession("Audit Iso Tenant B", "Owner B", "audit-iso-b@example.com", "password123");

        inviteActivateAndLogin(ownerB, "audit-iso-b-agent@example.com", "Iso Agent B", UserRole.AGENT, "agent-password123");

        // Tenant A performed no auditable action of its own — its log must stay empty regardless of tenant B's activity.
        mockMvc.perform(get("/api/v1/audit-log").session(ownerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }
}
