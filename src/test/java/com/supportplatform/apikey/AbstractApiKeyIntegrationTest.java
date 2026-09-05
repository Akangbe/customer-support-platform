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

    /**
     * Puts a template on the caller's tenant allowlist. Every send now goes
     * through that gate, so a test that wants a send to reach the gateway
     * has to register its template first — exactly as a real tenant does.
     */
    protected void approveTemplate(MockHttpSession session, String templateName) throws Exception {
        registerTemplate(session, templateName, "APPROVED");
    }

    protected void registerTemplate(MockHttpSession session, String templateName, String status) throws Exception {
        mockMvc.perform(post("/api/v1/whatsapp/templates")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"status\":\"%s\"}".formatted(templateName, status)))
                .andExpect(status().isCreated());
    }

    protected String sendRequestBody(String recipient, String templateName) {
        return """
                {"recipient":"%s","templateName":"%s"}
                """.formatted(recipient, templateName);
    }
}
