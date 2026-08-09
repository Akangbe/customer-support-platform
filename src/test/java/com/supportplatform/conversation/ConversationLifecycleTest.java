package com.supportplatform.conversation;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.conversation.dto.StartConversationRequest;
import com.supportplatform.conversation.dto.UpdatePriorityRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationLifecycleTest extends AbstractIntegrationTest {

    @Test
    void startingCreatesANewOpenConversation() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 1", "Conv Owner 1", "conv-owner-1@example.com", "password123");
        String customerId = createCustomer(owner, "+14155551001", "New Customer");

        mockMvc.perform(post("/api/v1/conversations")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartConversationRequest(java.util.UUID.fromString(customerId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.customerId").value(customerId));
    }

    @Test
    void startingAgainForTheSameCustomerReturnsTheSameConversation() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 2", "Conv Owner 2", "conv-owner-2@example.com", "password123");
        String customerId = createCustomer(owner, "+14155551002", "Repeat Customer");

        String firstId = startConversation(owner, customerId);
        String secondId = startConversation(owner, customerId);

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void closingAnOpenConversationSucceedsButClosingTwiceDoesNot() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 3", "Conv Owner 3", "conv-owner-3@example.com", "password123");
        String customerId = createCustomer(owner, "+14155551003", "Close Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/close").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/close").session(owner))
                .andExpect(status().isConflict());
    }

    @Test
    void reopeningAClosedConversationClearsItsAssignment() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 4", "Conv Owner 4", "conv-owner-4@example.com", "password123");
        String customerId = createCustomer(owner, "+14155551004", "Reopen Customer");
        String conversationId = startConversation(owner, customerId);
        String ownerId = extractUserId(owner);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + ownerId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/close").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/reopen").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assignedAgentId").doesNotExist());
    }

    @Test
    void reopeningANonClosedConversationIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 5", "Conv Owner 5", "conv-owner-5@example.com", "password123");
        String customerId = createCustomer(owner, "+14155551005", "Bad Reopen Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/reopen").session(owner))
                .andExpect(status().isConflict());
    }

    @Test
    void afterReopenStartingAgainReturnsTheReopenedConversationNotANewOne() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 6", "Conv Owner 6", "conv-owner-6@example.com", "password123");
        String customerId = createCustomer(owner, "+14155551006", "Full Loop Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/close").session(owner))
                .andExpect(status().isOk());

        String reopenedId = startConversation(owner, customerId);

        assertThat(reopenedId).isEqualTo(conversationId);
        mockMvc.perform(get("/api/v1/conversations/" + conversationId).session(owner))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void updatingPriorityWorks() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 7", "Conv Owner 7", "conv-owner-7@example.com", "password123");
        String customerId = createCustomer(owner, "+14155551007", "Priority Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(patch("/api/v1/conversations/" + conversationId + "/priority")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdatePriorityRequest(ConversationPriority.URGENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("URGENT"));
    }

    @Test
    void listingCanBeFilteredByStatus() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Conv Co 8", "Conv Owner 8", "conv-owner-8@example.com", "password123");
        String openCustomerId = createCustomer(owner, "+14155551008", "Open Customer");
        String closedCustomerId = createCustomer(owner, "+14155551009", "Closed Customer");
        startConversation(owner, openCustomerId);
        String closedConversationId = startConversation(owner, closedCustomerId);
        mockMvc.perform(post("/api/v1/conversations/" + closedConversationId + "/close").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/conversations").session(owner).param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customerId").value(openCustomerId));

        mockMvc.perform(get("/api/v1/conversations").session(owner).param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customerId").value(closedCustomerId));
    }
}
