package com.supportplatform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves Rule 3 for attachments, same as every other phase's isolation test. */
class AttachmentTenantIsolationTest extends AbstractStorageIntegrationTest {

    @Test
    void cannotReadAnotherTenantsAttachmentByGuessingItsId() throws Exception {
        MockHttpSession sessionA = registerTenantAndGetSession("Storage Iso Tenant A", "Owner A", "storage-iso-a@example.com", "password123");
        MockHttpSession sessionB = registerTenantAndGetSession("Storage Iso Tenant B", "Owner B", "storage-iso-b@example.com", "password123");

        MockMultipartFile file = new MockMultipartFile("file", "secret.txt", "text/plain", "tenant B's file".getBytes());
        var result = mockMvc.perform(multipart("/api/v1/attachments").file(file).session(sessionB))
                .andExpect(status().isCreated())
                .andReturn();
        String attachmentId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/attachments/" + attachmentId).session(sessionA))
                .andExpect(status().isNotFound());
    }
}
