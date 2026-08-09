package com.supportplatform.conversation;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.conversation.dto.StartConversationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves Rule 3 for the Conversation domain, same as the User and Customer isolation tests. */
class ConversationTenantIsolationTest extends AbstractIntegrationTest {

    @Test
    void conversationListingIsScopedToTheCallersTenant() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Conv Tenant A", "Owner A", "conv-isolation-a@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Conv Tenant B", "Owner B", "conv-isolation-b@example.com", "password123");

        String customerAId = createCustomer(sessionA, "+14155553001", "Customer A");
        String customerBId = createCustomer(sessionB, "+14155553002", "Customer B");
        startConversation(sessionA, customerAId);
        startConversation(sessionB, customerBId);

        mockMvc.perform(get("/api/v1/conversations").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customerId").value(customerAId));

        mockMvc.perform(get("/api/v1/conversations").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customerId").value(customerBId));
    }

    @Test
    void cannotReadAnotherTenantsConversationByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Conv Tenant C", "Owner C", "conv-isolation-c@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Conv Tenant D", "Owner D", "conv-isolation-d@example.com", "password123");

        String customerBId = createCustomer(sessionB, "+14155553003", "Customer D");
        String conversationBId = startConversation(sessionB, customerBId);

        mockMvc.perform(get("/api/v1/conversations/" + conversationBId).session(sessionA))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotCloseOrAssignAnotherTenantsConversationByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Conv Tenant E", "Owner E", "conv-isolation-e@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Conv Tenant F", "Owner F", "conv-isolation-f@example.com", "password123");

        String ownerAId = extractUserId(sessionA);
        String customerBId = createCustomer(sessionB, "+14155553004", "Customer F");
        String conversationBId = startConversation(sessionB, customerBId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationBId + "/close").session(sessionA))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/conversations/" + conversationBId + "/assign")
                        .session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + ownerAId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startingAConversationForAnotherTenantsCustomerIsRejected() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Conv Tenant G", "Owner G", "conv-isolation-g@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Conv Tenant H", "Owner H", "conv-isolation-h@example.com", "password123");

        String customerBId = createCustomer(sessionB, "+14155553005", "Customer H");

        mockMvc.perform(post("/api/v1/conversations")
                        .session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartConversationRequest(UUID.fromString(customerBId)))))
                .andExpect(status().isNotFound());
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
