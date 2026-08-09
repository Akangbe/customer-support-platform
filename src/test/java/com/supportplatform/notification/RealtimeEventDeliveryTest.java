package com.supportplatform.notification;

import com.supportplatform.conversation.dto.ConversationResponse;
import com.supportplatform.message.MessageService;
import com.supportplatform.message.dto.MessageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers realtime-domain.md §4: a REST-triggered state change reaches a subscribed WebSocket client. */
class RealtimeEventDeliveryTest extends AbstractRealtimeIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Test
    void assigningAConversationBroadcastsItsUpdatedState() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("RT Co 1", "RT Owner 1", "rt-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String ownerId = extractUserId(owner);
        String customerId = createCustomer(owner, "+14155557001", "RT Customer 1");

        StompSession stompSession = connectStomp(realLoginSessionCookie("rt-owner-1@example.com", "password123"));
        BlockingQueue<ConversationResponse> conversations = subscribe(stompSession,
                "/topic/tenants/" + tenantId + "/conversations", ConversationResponse.class);

        String conversationId = startConversation(owner, customerId);
        ConversationResponse created = conversations.poll(5, TimeUnit.SECONDS);
        assertThat(created).isNotNull();
        assertThat(created.id().toString()).isEqualTo(conversationId);

        assignConversation(owner, conversationId, ownerId);
        ConversationResponse assigned = conversations.poll(5, TimeUnit.SECONDS);
        assertThat(assigned).isNotNull();
        assertThat(assigned.assignedAgentId().toString()).isEqualTo(ownerId);
    }

    @Test
    void aNewInboundAndOutboundMessageBothBroadcast() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("RT Co 2", "RT Owner 2", "rt-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String customerId = createCustomer(owner, "+14155557002", "RT Customer 2");
        String conversationId = startConversation(owner, customerId);

        StompSession stompSession = connectStomp(realLoginSessionCookie("rt-owner-2@example.com", "password123"));
        BlockingQueue<MessageResponse> messages = subscribe(stompSession,
                "/topic/tenants/" + tenantId + "/messages", MessageResponse.class);

        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.RT1", "Hello");
        MessageResponse inbound = messages.poll(5, TimeUnit.SECONDS);
        assertThat(inbound).isNotNull();
        assertThat(inbound.body()).isEqualTo("Hello");

        sendMessage(owner, conversationId, "On it");
        MessageResponse outbound = messages.poll(5, TimeUnit.SECONDS);
        assertThat(outbound).isNotNull();
        assertThat(outbound.body()).isEqualTo("On it");
    }

    private void assignConversation(MockHttpSession session, String conversationId, String agentId) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk());
    }

    private void sendMessage(MockHttpSession session, String conversationId, String body) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated());
    }
}
