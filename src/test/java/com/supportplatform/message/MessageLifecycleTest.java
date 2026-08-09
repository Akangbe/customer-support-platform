package com.supportplatform.message;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.message.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers message-domain.md: send/list, the 24h service window, and ADR-012 inbound idempotency. */
class MessageLifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Test
    void sendingWithinTheServiceWindowPersistsAPendingOutboundMessage() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Msg Co 1", "Msg Owner 1", "msg-owner-1@example.com", "password123");
        String customerId = createCustomer(owner, "+14155554001", "Window Customer");
        String conversationId = startConversation(owner, customerId);
        openServiceWindow(owner, conversationId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("Hello there"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("OUTBOUND"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.body").value("Hello there"));

        mockMvc.perform(get("/api/v1/conversations/" + conversationId).session(owner))
                .andExpect(jsonPath("$.lastOutboundAt").exists());
    }

    @Test
    void sendingOutsideTheServiceWindowIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Msg Co 2", "Msg Owner 2", "msg-owner-2@example.com", "password123");
        String customerId = createCustomer(owner, "+14155554002", "No Window Customer");
        String conversationId = startConversation(owner, customerId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("Too soon"))))
                .andExpect(status().isConflict());
    }

    @Test
    void sendingOnAClosedConversationIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Msg Co 3", "Msg Owner 3", "msg-owner-3@example.com", "password123");
        String customerId = createCustomer(owner, "+14155554003", "Closed Send Customer");
        String conversationId = startConversation(owner, customerId);
        openServiceWindow(owner, conversationId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/close").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("Are you there?"))))
                .andExpect(status().isConflict());
    }

    @Test
    void listingReturnsBothInboundAndOutboundMessages() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Msg Co 4", "Msg Owner 4", "msg-owner-4@example.com", "password123");
        String customerId = createCustomer(owner, "+14155554004", "History Customer");
        String conversationId = startConversation(owner, customerId);
        openServiceWindow(owner, conversationId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("Reply"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void recordingTheSameInboundMessageTwiceIsANoOp() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Msg Co 5", "Msg Owner 5", "msg-owner-5@example.com", "password123");
        String customerId = createCustomer(owner, "+14155554005", "Dedupe Customer");
        String conversationId = startConversation(owner, customerId);
        UUID tenantId = extractTenantId(owner);
        String waMessageId = "wamid." + UUID.randomUUID();

        Message first = messageService.recordInbound(tenantId, UUID.fromString(conversationId), waMessageId, "Hi");
        Message second = messageService.recordInbound(tenantId, UUID.fromString(conversationId), waMessageId, "Hi");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    /** Test-only setup: a real inbound message is what opens the window; Phase 6 wires this to a webhook. */
    private void openServiceWindow(MockHttpSession session, String conversationId) {
        messageService.recordInbound(extractTenantId(session), UUID.fromString(conversationId),
                "wamid." + UUID.randomUUID(), "Hi, I need help");
    }
}
