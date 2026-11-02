package com.supportplatform.client;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.auth.dto.AcceptInviteRequest;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.notification.NotificationLogService;
import com.supportplatform.whatsapp.SendResult;
import com.supportplatform.whatsapp.WhatsAppGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two client businesses on one tenant, each with a login, plus a template the
 * operator keeps for itself. Everything a client reads must describe only its
 * own sending.
 */
class ClientPortalDataTest extends AbstractApiKeyIntegrationTest {

    private static final String TEMPLATE_A = "client_a_alert";
    private static final String TEMPLATE_B = "client_b_alert";
    private static final String OPERATOR_TEMPLATE = "operator_private";
    private static final String NUMBER_A = "+14155550701";
    private static final String NUMBER_B = "+14155550702";

    @MockitoBean
    private WhatsAppGateway gateway;

    @Autowired
    private NotificationLogService notificationLogService;

    private final AtomicInteger wamids = new AtomicInteger();

    private record Client(String key, MockHttpSession session) {
    }

    private record Fixture(MockHttpSession owner, UUID tenantId, Client a, Client b) {
    }

    @Test
    void overviewAndDailyStatsDescribeOnlyTheCallersSending() throws Exception {
        Fixture f = fixture("data-1");
        JsonNode delivered = send(f.a().key(), NUMBER_A, TEMPLATE_A);
        notificationLogService.applyDeliveryStatus(f.tenantId(), delivered.get("metaMessageId").asText(), "delivered", null);
        JsonNode read = send(f.a().key(), NUMBER_A, TEMPLATE_A);
        notificationLogService.applyDeliveryStatus(f.tenantId(), read.get("metaMessageId").asText(), "read", null);
        JsonNode failed = send(f.a().key(), NUMBER_A, TEMPLATE_A);
        notificationLogService.applyDeliveryStatus(f.tenantId(), failed.get("metaMessageId").asText(), "failed",
                "Meta error 131042: Business eligibility payment issue - fbtrace=SECRETTRACE");
        send(f.a().key(), "+14155550703", TEMPLATE_A);
        send(f.b().key(), NUMBER_B, TEMPLATE_B);

        MvcResult overview = mockMvc.perform(get("/api/v1/client/overview").session(f.a().session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSends").value(4))
                .andExpect(jsonPath("$.distinctRecipients").value(2))
                .andExpect(jsonPath("$.byStatus.delivered").value(1))
                .andExpect(jsonPath("$.byStatus.read").value(1))
                .andExpect(jsonPath("$.byStatus.failed").value(1))
                .andExpect(jsonPath("$.deliveryRate").value(0.5))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.failures[0].code").value("131042"))
                .andExpect(jsonPath("$.failures[0].category").value("PLATFORM"))
                .andExpect(jsonPath("$.failures[0].count").value(1))
                .andReturn();
        assertThat(overview.getResponse().getContentAsString()).doesNotContain("SECRETTRACE").doesNotContain("tenantId");

        mockMvc.perform(get("/api/v1/client/stats/daily").session(f.a().session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].sends").value(4))
                .andExpect(jsonPath("$.days[0].delivered").value(2))
                .andExpect(jsonPath("$.days[0].read").value(1))
                .andExpect(jsonPath("$.days[0].failed").value(1));

        mockMvc.perform(get("/api/v1/client/overview").session(f.b().session()))
                .andExpect(jsonPath("$.totalSends").value(1))
                .andExpect(jsonPath("$.failures.length()").value(0));
    }

    @Test
    void aNumberThatKeepsFailingIsListedMasked() throws Exception {
        Fixture f = fixture("data-2");
        for (int i = 0; i < 2; i++) {
            JsonNode sent = send(f.a().key(), NUMBER_A, TEMPLATE_A);
            notificationLogService.applyDeliveryStatus(f.tenantId(), sent.get("metaMessageId").asText(), "failed",
                    "Meta error 131026: Message undeliverable");
        }
        JsonNode once = send(f.a().key(), "+14155550703", TEMPLATE_A);
        notificationLogService.applyDeliveryStatus(f.tenantId(), once.get("metaMessageId").asText(), "failed",
                "Meta error 131026: Message undeliverable");

        MvcResult result = mockMvc.perform(get("/api/v1/client/overview").session(f.a().session()))
                .andExpect(jsonPath("$.repeatFailures.length()").value(1))
                .andExpect(jsonPath("$.repeatFailures[0].recipient").value("****0701"))
                .andExpect(jsonPath("$.repeatFailures[0].failedCount").value(2))
                .andExpect(jsonPath("$.repeatFailures[0].lastMessageId").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(NUMBER_A);
    }

    @Test
    void searchIsScopedMaskedFilteredAndPaged() throws Exception {
        Fixture f = fixture("data-3");
        for (int i = 0; i < 3; i++) {
            send(f.a().key(), NUMBER_A, TEMPLATE_A);
        }
        send(f.a().key(), "+14155550703", TEMPLATE_A);
        send(f.b().key(), NUMBER_B, TEMPLATE_B);

        JsonNode first = search(f.a(), "{\"size\":3}");
        assertThat(first.get("items")).hasSize(3);
        assertThat(first.toString()).doesNotContain(NUMBER_A).doesNotContain(TEMPLATE_B);
        assertThat(first.get("items").get(0).get("recipient").asText()).startsWith("****");

        JsonNode second = search(f.a(), "{\"size\":3,\"cursor\":\"%s\"}".formatted(first.get("nextCursor").asText()));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(first.toString()).doesNotContain(second.get("items").get(0).get("id").asText());

        assertThat(search(f.a(), "{\"recipient\":\"%s\"}".formatted(NUMBER_A)).get("items")).hasSize(3);
        assertThat(search(f.a(), "{\"recipient\":\"14155550701\"}").get("items")).hasSize(3);
        assertThat(search(f.a(), "{\"status\":\"FAILED\"}").get("items")).isEmpty();
        // Another client's number and template find nothing, as if they did not exist.
        assertThat(search(f.a(), "{\"recipient\":\"%s\"}".formatted(NUMBER_B)).get("items")).isEmpty();
        assertThat(search(f.a(), "{\"template\":\"%s\"}".formatted(TEMPLATE_B)).get("items")).isEmpty();
    }

    @Test
    void openingAMessageShowsTheNumberOnlyToItsOwnerAndIsAudited() throws Exception {
        Fixture f = fixture("data-4");
        String id = send(f.a().key(), NUMBER_A, TEMPLATE_A).get("notificationId").asText();

        mockMvc.perform(get("/api/v1/client/messages/" + id).session(f.a().session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").value(NUMBER_A));
        mockMvc.perform(get("/api/v1/client/messages/" + id).session(f.b().session()))
                .andExpect(status().isNotFound());

        MvcResult audit = mockMvc.perform(get("/api/v1/audit-log").session(f.owner()))
                .andExpect(status().isOk())
                .andReturn();
        String body = audit.getResponse().getContentAsString();
        assertThat(body).contains("CLIENT_RECIPIENT_VIEWED").contains(id);
        assertThat(body.split("CLIENT_RECIPIENT_VIEWED", -1)).hasSize(2);
    }

    @Test
    void templatesListOnlyWhatTheCallerHasSent() throws Exception {
        Fixture f = fixture("data-5");
        send(f.a().key(), NUMBER_A, TEMPLATE_A);
        send(f.b().key(), NUMBER_B, TEMPLATE_B);

        MvcResult result = mockMvc.perform(get("/api/v1/client/templates").session(f.a().session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(TEMPLATE_A))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].sends").value(1))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(TEMPLATE_B).doesNotContain(OPERATOR_TEMPLATE);
    }

    @Test
    void keysListOnlyTheCallersOwnWithoutSecrets() throws Exception {
        Fixture f = fixture("data-6");

        MvcResult result = mockMvc.perform(get("/api/v1/client/keys").session(f.a().session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Client A"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Client B").doesNotContain("secret").doesNotContain("rd_live_");
    }

    @Test
    void badInputIsRefusedCleanly() throws Exception {
        Fixture f = fixture("data-7");

        mockMvc.perform(get("/api/v1/client/overview").param("from", "2026-01-01").param("to", "2026-12-31")
                        .session(f.a().session()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(searchRequest(f.a(), "{\"cursor\":\"not-a-real-cursor\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(searchRequest(f.a(), "{\"recipient\":\"' OR 1=1 --\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(searchRequest(f.a(), "{\"size\":100000}"))
                .andExpect(status().isBadRequest());
    }

    private Fixture fixture(String suffix) throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Portal Co " + suffix, "Owner",
                "owner@" + suffix + ".example.com", "password123");
        connectWhatsApp(owner, "pn-" + suffix);
        approveTemplate(owner, TEMPLATE_A);
        approveTemplate(owner, TEMPLATE_B);
        approveTemplate(owner, OPERATOR_TEMPLATE);
        when(gateway.sendTemplate(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> SendResult.success("wamid.DATA" + wamids.incrementAndGet()));
        return new Fixture(owner, extractTenantId(owner), client(owner, "Client A", "a@" + suffix + ".example.com"),
                client(owner, "Client B", "b@" + suffix + ".example.com"));
    }

    private Client client(MockHttpSession owner, String name, String email) throws Exception {
        MvcResult key = mockMvc.perform(post("/api/v1/api-keys").session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = objectMapper.readTree(key.getResponse().getContentAsString());
        MvcResult invite = mockMvc.perform(post("/api/v1/client-accounts/" + created.get("key").get("clientAccountId").asText() + "/users")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"Person\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(invite.getResponse().getContentAsString()).get("inviteToken").asText();
        mockMvc.perform(post("/api/v1/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptInviteRequest(token, "password123"))))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        return new Client(created.get("apiKey").asText(), (MockHttpSession) login.getRequest().getSession());
    }

    private JsonNode send(String key, String recipient, String template) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendRequestBody(recipient, template)))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder searchRequest(Client client, String body) {
        return post("/api/v1/client/messages/search").session(client.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private JsonNode search(Client client, String body) throws Exception {
        MvcResult result = mockMvc.perform(searchRequest(client, body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
