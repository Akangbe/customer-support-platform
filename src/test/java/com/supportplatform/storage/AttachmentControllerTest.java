package com.supportplatform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers storage-domain.md §4, §7: upload, then a presigned URL that actually serves the uploaded bytes back. */
class AttachmentControllerTest extends AbstractStorageIntegrationTest {

    @Test
    void uploadingThenReadingReturnsAWorkingPresignedUrl() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Storage Co 1", "Storage Owner 1", "storage-owner-1@example.com", "password123");
        byte[] content = "hello attachment".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", content);

        String attachmentId = uploadAndExtractId(owner, file);

        String url = mockMvc.perform(get("/api/v1/attachments/" + attachmentId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andReturn().getResponse().getContentAsString();

        String presignedUrl = objectMapper.readTree(url).get("url").asText();
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(presignedUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(content);
    }

    @Test
    void anEmptyFileIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Storage Co 2", "Storage Owner 2", "storage-owner-2@example.com", "password123");
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/v1/attachments").file(empty).session(owner))
                .andExpect(status().isBadRequest());
    }

    private String uploadAndExtractId(MockHttpSession session, MockMultipartFile file) throws Exception {
        var result = mockMvc.perform(multipart("/api/v1/attachments").file(file).session(session))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
