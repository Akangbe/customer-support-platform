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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /api/v1/api-keys/{id}.
 *
 * <p>Exists so that changing who hears about a key's traffic is an ordinary
 * operation rather than a hand-written UPDATE against production. The
 * alternative — reissuing the key — would have a live integration swap
 * credentials for an administrative edit.
 */
class ApiKeyUpdateTest extends AbstractApiKeyIntegrationTest {

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Test
    void aContactEmailCanBeSetOnAKeyThatDidNotHaveOne() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Patch Co 1", "Patch Owner 1",
                "patch-owner-1@example.com", "password123");
        UUID keyId = createKey(owner, "{\"name\":\"Trustpady production\"}");

        // The motivating case exactly: a key issued before contact_email
        // existed, now needing an address so its volume alerts reach the
        // integrator rather than only the tenant.
        mockMvc.perform(patch("/api/v1/api-keys/" + keyId)
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactEmail\":\"integrator@partner.example\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactEmail").value("integrator@partner.example"))
                .andExpect(jsonPath("$.name").value("Trustpady production"));

        assertThat(apiKeyRepository.findById(keyId).orElseThrow().getContactEmail())
                .isEqualTo("integrator@partner.example");
    }

    @Test
    void omittedFieldsAreLeftAloneAndAnEmptyContactClearsIt() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Patch Co 2", "Patch Owner 2",
                "patch-owner-2@example.com", "password123");
        UUID keyId = createKey(owner,
                "{\"name\":\"Partner key\",\"contactEmail\":\"ops@partner.example\",\"rateLimit\":120}");

        // Changing one setting must not blank the others by not restating
        // them — that is the whole reason this is PATCH and not PUT.
        mockMvc.perform(patch("/api/v1/api-keys/" + keyId)
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rateLimit\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimit").value(300))
                .andExpect(jsonPath("$.name").value("Partner key"))
                .andExpect(jsonPath("$.contactEmail").value("ops@partner.example"));

        // Removing has to be expressible: a partner whose ops contact leaves
        // should stop receiving this tenant's volume alerts.
        mockMvc.perform(patch("/api/v1/api-keys/" + keyId)
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactEmail\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactEmail").doesNotExist());

        assertThat(apiKeyRepository.findById(keyId).orElseThrow().getContactEmail()).isNull();
    }

    @Test
    void aMalformedAddressIsRejected() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Patch Co 3", "Patch Owner 3",
                "patch-owner-3@example.com", "password123");
        UUID keyId = createKey(owner, "{\"name\":\"Partner key\"}");

        mockMvc.perform(patch("/api/v1/api-keys/" + keyId)
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactEmail\":\"not-an-address\"}"))
                .andExpect(status().isBadRequest());

        assertThat(apiKeyRepository.findById(keyId).orElseThrow().getContactEmail()).isNull();
    }

    @Test
    void aKeyBelongingToAnotherTenantIsNotFound() throws Exception {
        MockHttpSession firstOwner = registerTenantAndGetSession("Patch Co 4", "Patch Owner 4",
                "patch-owner-4@example.com", "password123");
        UUID foreignKeyId = createKey(firstOwner, "{\"name\":\"Their key\"}");

        MockHttpSession secondOwner = registerTenantAndGetSession("Patch Co 5", "Patch Owner 5",
                "patch-owner-5@example.com", "password123");

        // Rule 3. A 404 rather than a 403, so an id from another tenant is
        // indistinguishable from one that never existed and nobody can probe
        // for which keys are out there.
        mockMvc.perform(patch("/api/v1/api-keys/" + foreignKeyId)
                        .session(secondOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactEmail\":\"attacker@example.com\"}"))
                .andExpect(status().isNotFound());

        assertThat(apiKeyRepository.findById(foreignKeyId).orElseThrow().getContactEmail()).isNull();
    }

    @Test
    void anAgentCannotEditAKey() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Patch Co 6", "Patch Owner 6",
                "patch-owner-6@example.com", "password123");
        UUID keyId = createKey(owner, "{\"name\":\"Partner key\"}");
        MockHttpSession agent = inviteActivateAndLogin(owner, "patch-agent-6@example.com", "Patch Agent 6",
                UserRole.AGENT, "password123");

        // Same authority as issuing a key or reaching for the kill switch.
        mockMvc.perform(patch("/api/v1/api-keys/" + keyId)
                        .session(agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactEmail\":\"agent@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theEditIsAudited() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Patch Co 7", "Patch Owner 7",
                "patch-owner-7@example.com", "password123");
        UUID keyId = createKey(owner, "{\"name\":\"Partner key\"}");

        mockMvc.perform(patch("/api/v1/api-keys/" + keyId)
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactEmail\":\"ops@partner.example\"}"))
                .andExpect(status().isOk());

        // contact_email decides who hears about a key's traffic, so changing
        // it can redirect an alert away from whoever should see it. That is
        // exactly the sort of change an audit log exists for — and the entry
        // names the field that moved, never the address itself.
        MvcResult audit = mockMvc.perform(get("/api/v1/audit-log").session(owner))
                .andExpect(status().isOk())
                .andReturn();
        String body = audit.getResponse().getContentAsString();
        assertThat(body).contains("API_KEY_UPDATED").contains("contactEmail");
        assertThat(body).doesNotContain("ops@partner.example");
    }

    private UUID createKey(MockHttpSession session, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/api-keys")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        // CreatedApiKeyResponse nests the safe view under "key"; only the
        // plaintext secret sits at the top level.
        return UUID.fromString(node.get("key").get("id").asText());
    }
}
