package com.supportplatform.message;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.message.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves Rule 3 for the Message domain, same as the Conversation, Customer, and User isolation tests. */
class MessageTenantIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Test
    void cannotSendOnAnotherTenantsConversationByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Msg Tenant A", "Owner A", "msg-isolation-a@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Msg Tenant B", "Owner B", "msg-isolation-b@example.com", "password123");

        String customerBId = createCustomer(sessionB, "+14155555001", "Customer B");
        String conversationBId = startConversation(sessionB, customerBId);
        messageService.recordInbound(extractTenantId(sessionB), UUID.fromString(conversationBId),
                "wamid." + UUID.randomUUID(), "Hi");

        mockMvc.perform(post("/api/v1/conversations/" + conversationBId + "/messages")
                        .session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("Sneaky reply"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotListAnotherTenantsConversationMessages() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Msg Tenant C", "Owner C", "msg-isolation-c@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Msg Tenant D", "Owner D", "msg-isolation-d@example.com", "password123");

        String customerBId = createCustomer(sessionB, "+14155555002", "Customer D");
        String conversationBId = startConversation(sessionB, customerBId);

        mockMvc.perform(get("/api/v1/conversations/" + conversationBId + "/messages").session(sessionA))
                .andExpect(status().isNotFound());
    }
}
