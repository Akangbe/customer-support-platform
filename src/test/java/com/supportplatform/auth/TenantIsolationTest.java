package com.supportplatform.auth;

import com.supportplatform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the tenant isolation invariant (Architecture Principles, Rule 3):
 * a user authenticated in one tenant can neither see nor act on another
 * tenant's users, including by guessing/reusing a real ID from the other
 * tenant. This is a required completion criterion for Phase 2, not an
 * optional nicety.
 */
class TenantIsolationTest extends AbstractIntegrationTest {

    @Test
    void userListingIsScopedToTheCallersTenant() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Tenant A", "Owner A", "isolation-owner-a@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Tenant B", "Owner B", "isolation-owner-b@example.com", "password123");

        mockMvc.perform(get("/api/v1/users").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("isolation-owner-a@example.com"));

        mockMvc.perform(get("/api/v1/users").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("isolation-owner-b@example.com"));
    }

    @Test
    void cannotDisableAnotherTenantsUserByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Tenant C", "Owner C", "isolation-owner-c@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Tenant D", "Owner D", "isolation-owner-d@example.com", "password123");

        String ownerBId = extractId(sessionB);

        mockMvc.perform(post("/api/v1/users/" + ownerBId + "/disable").session(sessionA))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotChangeAnotherTenantsUserRoleByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Tenant E", "Owner E", "isolation-owner-e@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Tenant F", "Owner F", "isolation-owner-f@example.com", "password123");

        String ownerBId = extractId(sessionB);

        mockMvc.perform(patch("/api/v1/users/" + ownerBId + "/role")
                        .session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AGENT\"}"))
                .andExpect(status().isNotFound());
    }

    private String extractId(MockHttpSession session) throws Exception {
        var result = mockMvc.perform(get("/api/v1/users/me").session(session)).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
