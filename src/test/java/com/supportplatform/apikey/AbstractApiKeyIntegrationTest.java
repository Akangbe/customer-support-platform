package com.supportplatform.apikey;

import com.supportplatform.AbstractIntegrationTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared setup for tests that need a tenant holding a usable API key. */
public abstract class AbstractApiKeyIntegrationTest extends AbstractIntegrationTest {

    /** Issues a key for the caller's tenant and returns the plaintext, which is only ever available here. */
    protected String issueApiKey(MockHttpSession session, String name) throws Exception {
        return issueApiKey(session, name, null);
    }

    protected String issueApiKey(MockHttpSession session, String name, Integer rateLimit) throws Exception {
        String body = rateLimit == null
                ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"rateLimit\":%d}".formatted(name, rateLimit);

        MvcResult result = mockMvc.perform(post("/api/v1/api-keys")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("apiKey").asText();
    }

    /** Connects WhatsApp for the caller's tenant, so a notification send has a number to go out on. */
    protected void connectWhatsApp(MockHttpSession session, String phoneNumberId) throws Exception {
        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"%s","wabaId":"waba-notify","accessToken":"tenant-access-token"}
                                """.formatted(phoneNumberId)))
                .andExpect(status().isOk());
    }

    protected String sendRequestBody(String recipient, String templateName) {
        return """
                {"recipient":"%s","templateName":"%s"}
                """.formatted(recipient, templateName);
    }
}
