package com.supportplatform.whatsapp;

import com.supportplatform.AbstractIntegrationTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared setup for WhatsApp integration tests: connecting a tenant and
 * signing a webhook payload the same way Meta would, using the
 * {@code local-dev-app-secret-change-me} default from application.yml
 * (no profile overlay is active in tests, so that default is what the
 * app actually verifies against).
 */
abstract class AbstractWhatsAppIntegrationTest extends AbstractIntegrationTest {

    static final String APP_SECRET = "local-dev-app-secret-change-me";

    /** Connects WhatsApp for the caller's tenant with a known phone_number_id, returning that id. */
    protected String connectWhatsApp(MockHttpSession session, String phoneNumberId) throws Exception {
        mockMvc.perform(post("/api/v1/whatsapp/connection")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"%s","wabaId":"waba-1","accessToken":"test-access-token"}
                                """.formatted(phoneNumberId)))
                .andReturn();
        return phoneNumberId;
    }

    protected MvcResult postSignedWebhook(String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(payload))
                        .content(payload))
                .andReturn();
    }

    protected String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hmac);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
