package com.supportplatform.conversation;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.conversation.dto.StartConversationRequest;
import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers ADR-017: self-claim for anyone, privileged reassignment for Owner/Admin/Manager. */
class ConversationAssignmentTest extends AbstractIntegrationTest {

    @Test
    void anyoneCanClaimAnUnassignedConversation() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Assign Co 1", "Assign Owner 1", "assign-owner-1@example.com", "password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "assign-agent-1@example.com", "Assign Agent 1", UserRole.AGENT, "agent-password123");
        String agentId = extractUserId(agent);
        String customerId = createCustomer(owner, "+14155552001", "Claim Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(agent).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignedAgentId").value(agentId));
    }

    @Test
    void anAgentCannotReassignSomeoneElsesClaimedConversation() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Assign Co 2", "Assign Owner 2", "assign-owner-2@example.com", "password123");
        MockHttpSession agentA = inviteActivateAndLogin(owner, "assign-agent-a@example.com", "Agent A", UserRole.AGENT, "agent-password123");
        MockHttpSession agentB = inviteActivateAndLogin(owner, "assign-agent-b@example.com", "Agent B", UserRole.AGENT, "agent-password123");
        String agentAId = extractUserId(agentA);
        String agentBId = extractUserId(agentB);
        String customerId = createCustomer(owner, "+14155552002", "Steal Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(agentA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentAId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(agentB).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentBId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanReassignAnAlreadyClaimedConversation() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Assign Co 3", "Assign Owner 3", "assign-owner-3@example.com", "password123");
        MockHttpSession manager = inviteActivateAndLogin(owner, "assign-manager-1@example.com", "Manager 1", UserRole.MANAGER, "manager-password123");
        MockHttpSession agentA = inviteActivateAndLogin(owner, "assign-agent-c@example.com", "Agent C", UserRole.AGENT, "agent-password123");
        MockHttpSession agentB = inviteActivateAndLogin(owner, "assign-agent-d@example.com", "Agent D", UserRole.AGENT, "agent-password123");
        String agentAId = extractUserId(agentA);
        String agentBId = extractUserId(agentB);
        String customerId = createCustomer(owner, "+14155552003", "Reassign Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(agentA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentAId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentBId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAgentId").value(agentBId));
    }

    @Test
    void theAssignedAgentCanUnassignThemselvesButAPeerCannot() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Assign Co 4", "Assign Owner 4", "assign-owner-4@example.com", "password123");
        MockHttpSession agentA = inviteActivateAndLogin(owner, "assign-agent-e@example.com", "Agent E", UserRole.AGENT, "agent-password123");
        MockHttpSession agentB = inviteActivateAndLogin(owner, "assign-agent-f@example.com", "Agent F", UserRole.AGENT, "agent-password123");
        String agentAId = extractUserId(agentA);
        String customerId = createCustomer(owner, "+14155552004", "Unassign Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(agentA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentAId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/unassign").session(agentB))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/unassign").session(agentA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assignedAgentId").doesNotExist());
    }

    @Test
    void cannotAssignToANonexistentUser() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Assign Co 5", "Assign Owner 5", "assign-owner-5@example.com", "password123");
        String customerId = createCustomer(owner, "+14155552005", "Ghost Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotAssignAClosedConversation() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Assign Co 6", "Assign Owner 6", "assign-owner-6@example.com", "password123");
        String ownerId = extractUserId(owner);
        String customerId = createCustomer(owner, "+14155552006", "Closed Assign Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/close").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + ownerId + "\"}"))
                .andExpect(status().isConflict());
    }

    private String startConversation(MockHttpSession session, String customerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartConversationRequest(UUID.fromString(customerId)))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
