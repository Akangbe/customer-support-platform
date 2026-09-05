package com.supportplatform.apikey;

import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Issuing, listing and revoking tenant API keys, and who is allowed to. */
class ApiKeyManagementTest extends AbstractApiKeyIntegrationTest {

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Test
    void anOwnerGetsThePlaintextKeyExactlyOnceAndOnlyTheHashIsStored() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Key Co 1", "Key Owner 1", "key-owner-1@example.com", "password123");

        MvcResult created = mockMvc.perform(post("/api/v1/api-keys")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Trustpady prod\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String plaintext = body.get("apiKey").asText();
        String keyId = body.get("key").get("keyId").asText();

        assertThat(plaintext).startsWith("rd_live_" + keyId + ".");
        assertThat(body.get("key").has("secret")).isFalse();
        assertThat(body.get("key").has("secretHash")).isFalse();
        assertThat(body.get("key").get("rateLimit").asInt()).isEqualTo(60);
        assertThat(body.get("key").get("active").asBoolean()).isTrue();

        // The stored row holds only a hash: neither half of the plaintext appears in it.
        ApiKey stored = apiKeyRepository.findByKeyId(keyId).orElseThrow();
        String secret = plaintext.substring(plaintext.indexOf('.') + 1);
        assertThat(stored.getSecretHash()).isNotEqualTo(secret).doesNotContain(secret);

        // ...and listing it back never surfaces the secret again.
        mockMvc.perform(get("/api/v1/api-keys").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyId").value(keyId))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].apiKey").doesNotExist());
    }

    @Test
    void aCustomRateLimitIsHonoured() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Key Co 2", "Key Owner 2", "key-owner-2@example.com", "password123");

        mockMvc.perform(post("/api/v1/api-keys")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"High volume\",\"rateLimit\":500}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key.rateLimit").value(500));
    }

    @Test
    void anAgentCannotIssueAKey() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Key Co 3", "Key Owner 3", "key-owner-3@example.com", "password123");
        MockHttpSession agent = inviteActivateAndLogin(owner, "key-agent-3@example.com", "Key Agent 3",
                UserRole.AGENT, "password123");

        mockMvc.perform(post("/api/v1/api-keys")
                        .session(agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sneaky\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanIssueAKey() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Key Co 4", "Key Owner 4", "key-owner-4@example.com", "password123");
        MockHttpSession admin = inviteActivateAndLogin(owner, "key-admin-4@example.com", "Key Admin 4",
                UserRole.ADMIN, "password123");

        mockMvc.perform(post("/api/v1/api-keys")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Admin issued\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void revokingFlipsTheKillSwitchAndStampsRevokedAt() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Key Co 5", "Key Owner 5", "key-owner-5@example.com", "password123");
        String plaintext = issueApiKey(owner, "To be revoked");
        String keyId = plaintext.substring("rd_live_".length(), plaintext.indexOf('.'));
        UUID id = apiKeyRepository.findByKeyId(keyId).orElseThrow().getId();

        mockMvc.perform(post("/api/v1/api-keys/" + id + "/deactivate").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());
    }

    @Test
    void oneTenantCannotRevokeAnothersKey() throws Exception {
        MockHttpSession ownerA = registerTenantAndGetSession("Key Co 6a", "Key Owner 6a", "key-owner-6a@example.com", "password123");
        MockHttpSession ownerB = registerTenantAndGetSession("Key Co 6b", "Key Owner 6b", "key-owner-6b@example.com", "password123");

        String plaintextA = issueApiKey(ownerA, "Tenant A key");
        String keyIdA = plaintextA.substring("rd_live_".length(), plaintextA.indexOf('.'));
        UUID idA = apiKeyRepository.findByKeyId(keyIdA).orElseThrow().getId();

        // Rule 3: B's session cannot reach A's row, so this is a 404, not a 403 —
        // B is told nothing about whether that id exists.
        mockMvc.perform(post("/api/v1/api-keys/" + idA + "/deactivate").session(ownerB))
                .andExpect(status().isNotFound());

        assertThat(apiKeyRepository.findByKeyId(keyIdA).orElseThrow().isActive()).isTrue();
    }

    @Test
    void aTenantOnlySeesItsOwnKeys() throws Exception {
        MockHttpSession ownerA = registerTenantAndGetSession("Key Co 7a", "Key Owner 7a", "key-owner-7a@example.com", "password123");
        MockHttpSession ownerB = registerTenantAndGetSession("Key Co 7b", "Key Owner 7b", "key-owner-7b@example.com", "password123");

        String plaintextA = issueApiKey(ownerA, "A only");
        String keyIdA = plaintextA.substring("rd_live_".length(), plaintextA.indexOf('.'));

        MvcResult listB = mockMvc.perform(get("/api/v1/api-keys").session(ownerB))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(listB.getResponse().getContentAsString()).doesNotContain(keyIdA);
    }
}
