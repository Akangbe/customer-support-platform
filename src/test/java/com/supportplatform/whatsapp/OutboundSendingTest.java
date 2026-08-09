package com.supportplatform.whatsapp;

import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import com.supportplatform.message.MessageService;
import com.supportplatform.message.MessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers whatsapp-domain.md §6: the outbound sender consuming PENDING messages via the gateway boundary. */
class OutboundSendingTest extends AbstractWhatsAppIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private OutboundMessageSender outboundMessageSender;
    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aSuccessfulSendMarksTheMessageSent() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Out Co 1", "Out Owner 1", "out-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "out-pn-1001");
        String customerId = createCustomer(owner, "+14155559001", "Out Customer 1");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.SEED1", "hi");

        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.success("wamid.OUT1"));

        String messageId = sendOutbound(owner, conversationId, "Thanks for reaching out");
        outboundMessageSender.sendPending();

        Message message = messageRepository.findById(UUID.fromString(messageId)).orElseThrow();
        assertThat(message.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message.getWaMessageId()).isEqualTo("wamid.OUT1");
    }

    @Test
    void aFailedSendBacksOffWithoutFailingImmediately() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Out Co 2", "Out Owner 2", "out-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "out-pn-1002");
        String customerId = createCustomer(owner, "+14155559002", "Out Customer 2");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.SEED2", "hi");

        when(gateway.sendText(any(), anyString(), anyString())).thenReturn(SendResult.failure("temporary Meta outage"));

        String messageId = sendOutbound(owner, conversationId, "Will retry");
        outboundMessageSender.sendPending();

        Message message = messageRepository.findById(UUID.fromString(messageId)).orElseThrow();
        assertThat(message.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(message.getAttemptCount()).isEqualTo(1);
        assertThat(message.getNextAttemptAt()).isNotNull();
        assertThat(message.getFailureReason()).isEqualTo("temporary Meta outage");
    }

    @Test
    void sendingWithNoWhatsAppConnectionFailsImmediatelyWithoutCallingTheGateway() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Out Co 3", "Out Owner 3", "out-owner-3@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String customerId = createCustomer(owner, "+14155559003", "Out Customer 3");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.SEED3", "hi");

        String messageId = sendOutbound(owner, conversationId, "Nobody will get this");
        outboundMessageSender.sendPending();

        Message message = messageRepository.findById(UUID.fromString(messageId)).orElseThrow();
        assertThat(message.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(message.getFailureReason()).isEqualTo("WhatsApp is not connected for this tenant");
    }

    private String sendOutbound(MockHttpSession session, String conversationId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
