package com.supportplatform.whatsapp;

import com.supportplatform.message.Message;
import com.supportplatform.message.MessageRepository;
import com.supportplatform.message.MessageService;
import com.supportplatform.message.MessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers storage-domain.md §5 / ADR-020: an outbound message with an attachment sends via a presigned link, not sendText. */
class WhatsAppMediaOutboundTest extends AbstractWhatsAppMediaIntegrationTest {

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private OutboundMessageSender outboundMessageSender;
    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageRepository messageRepository;

    @Test
    void aMessageWithAnAttachmentSendsAsMediaViaAPresignedLink() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Media Out Co 1", "Media Owner 1", "media-out-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        connectWhatsApp(owner, "media-out-pn-1");
        String customerId = createCustomer(owner, "+14155559101", "Media Out Customer 1");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.MEDIAOUT-SEED", "hi");

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes());
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/attachments").file(file).session(owner))
                .andExpect(status().isCreated())
                .andReturn();
        String attachmentId = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("id").asText();

        when(gateway.sendMedia(any(), anyString(), eq("image"), any(URI.class), anyString()))
                .thenReturn(SendResult.success("wamid.MEDIAOUT1"));

        // sendPending() drains the whole outbox, not just this tenant's — it is
        // a background poller, so it is deliberately not tenant-scoped. Earlier
        // test classes share this database and leave their own PENDING text
        // messages behind, so these have to return something rather than a mock's
        // default null. The assertion below is still only about sendMedia.
        when(gateway.sendText(any(), anyString(), anyString()))
                .thenReturn(SendResult.success("wamid.MEDIAOUT-BYSTANDER"));
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(SendResult.success("wamid.MEDIAOUT-BYSTANDER"));

        MvcResult sendResult = mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"" + attachmentId + "\",\"body\":\"check this out\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sent = objectMapper.readTree(sendResult.getResponse().getContentAsString());
        UUID messageId = UUID.fromString(sent.get("id").asText());

        outboundMessageSender.sendPending();

        verify(gateway).sendMedia(any(), anyString(), eq("image"), any(URI.class), eq("check this out"));
        Message message = messageRepository.findById(messageId).orElseThrow();
        assertThat(message.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message.getWaMessageId()).isEqualTo("wamid.MEDIAOUT1");
    }
}
