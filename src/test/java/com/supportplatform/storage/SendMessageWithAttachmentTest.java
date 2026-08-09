package com.supportplatform.storage;

import com.supportplatform.message.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers storage-domain.md §2, §4: sending a message that references an uploaded attachment. */
class SendMessageWithAttachmentTest extends AbstractStorageIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Test
    void sendingWithAnAttachmentAndNoCaptionSucceeds() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Attach Msg Co 1", "Owner 1", "attach-msg-owner-1@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String customerId = createCustomer(owner, "+14155553301", "Attach Customer 1");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.ATTACH1", "hi");

        String attachmentId = upload(owner, "photo.jpg", "image/jpeg", "bytes-here");

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"" + attachmentId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentId").value(attachmentId));

        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages").session(owner))
                .andExpect(jsonPath("$.content[1].attachmentId").value(attachmentId));
    }

    @Test
    void reusingAnAlreadyLinkedAttachmentIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Attach Msg Co 2", "Owner 2", "attach-msg-owner-2@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String customerId = createCustomer(owner, "+14155553302", "Attach Customer 2");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.ATTACH2", "hi");

        String attachmentId = upload(owner, "doc.pdf", "application/pdf", "pdf-bytes");

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"" + attachmentId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":\"" + attachmentId + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void sendingWithNeitherBodyTemplateNorAttachmentIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Attach Msg Co 3", "Owner 3", "attach-msg-owner-3@example.com", "password123");
        UUID tenantId = extractTenantId(owner);
        String customerId = createCustomer(owner, "+14155553303", "Attach Customer 3");
        String conversationId = startConversation(owner, customerId);
        messageService.recordInbound(tenantId, UUID.fromString(conversationId), "wamid.ATTACH3", "hi");

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private String upload(MockHttpSession session, String fileName, String contentType, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, contentType, content.getBytes());
        var result = mockMvc.perform(multipart("/api/v1/attachments").file(file).session(session))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
