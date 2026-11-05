package com.supportplatform.client;

import com.supportplatform.apikey.AbstractApiKeyIntegrationTest;
import com.supportplatform.auth.dto.AcceptInviteRequest;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.user.UserRole;
import com.supportplatform.user.dto.InviteUserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A person at a client business signs in, and can reach their own portal and nothing else. */
class ClientLoginTest extends AbstractApiKeyIntegrationTest {

    /** The only mapped routes a CLIENT session may get past security on. */
    private static final Set<String> OPEN_TO_CLIENT = Set.of(
            "/api/v1/users/me",
            "/api/v1/auth/login", "/api/v1/auth/register-tenant", "/api/v1/auth/accept-invite",
            "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm",
            "/api/v1/whatsapp/webhook", "/error");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void aClientUserSignsInAndSeesOnlyTheirAccountName() throws Exception {
        MockHttpSession owner = tenant("login-1");
        String accountId = account(owner, "Client One");
        String token = invite(owner, accountId, "person@login-1.example.com");

        accept(token);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("person@login-1.example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CLIENT"))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andReturn();
        MockHttpSession client = (MockHttpSession) login.getRequest().getSession();

        mockMvc.perform(get("/api/v1/client/me").session(client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("Client One"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    /** Fails on any new route that a CLIENT session can reach, so isolation cannot lapse by omission. */
    @Test
    void aClientSessionIsRefusedOnEveryOtherRoute() throws Exception {
        MockHttpSession client = clientSession("login-2");
        List<String> reachable = new ArrayList<>();

        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (OPEN_TO_CLIENT.contains(pattern) || pattern.startsWith("/api/v1/client/")) {
                    continue;
                }
                String uri = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                // This prefix is the API-key chain, which ignores sessions altogether.
                int refused = pattern.startsWith("/api/v1/notifications") ? 401 : 403;
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    int actual = mockMvc.perform(request(HttpMethod.valueOf(method.name()), uri).session(client))
                            .andReturn().getResponse().getStatus();
                    if (actual != refused) {
                        reachable.add(method + " " + pattern + " -> " + actual);
                    }
                }
            }
        }

        assertThat(reachable).isEmpty();
        assertThat(handlerMapping.getHandlerMethods()).isNotEmpty();
    }

    @Test
    void aClientSessionCannotOpenTheRealtimeSocket() throws Exception {
        mockMvc.perform(get("/ws").session(clientSession("login-3")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffAndStrangersCannotUseThePortalApi() throws Exception {
        mockMvc.perform(get("/api/v1/client/me").session(tenant("login-4")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/client/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disablingAClientUserEndsTheirAccessImmediately() throws Exception {
        MockHttpSession owner = tenant("login-5");
        String accountId = account(owner, "Client Five");
        String token = invite(owner, accountId, "person@login-5.example.com");
        MockHttpSession client = acceptAndLogin(token, "person@login-5.example.com");
        JsonNode users = list(owner, accountId);

        mockMvc.perform(post("/api/v1/users/" + users.get(0).get("id").asText() + "/disable").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/client/me").session(client))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clientUsersStayOffTheTeamAndOutOfRoleChanges() throws Exception {
        MockHttpSession owner = tenant("login-6");
        String accountId = account(owner, "Client Six");
        invite(owner, accountId, "person@login-6.example.com");
        String clientUserId = list(owner, accountId).get(0).get("id").asText();

        MvcResult team = mockMvc.perform(get("/api/v1/users").session(owner))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(team.getResponse().getContentAsString()).doesNotContain("person@login-6.example.com");

        mockMvc.perform(patch("/api/v1/users/" + clientUserId + "/role").session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/users/invite").session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest(
                                "other@login-6.example.com", "Other", UserRole.CLIENT))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAnOwnerOrAdminOfTheSameTenantCanInviteAClientUser() throws Exception {
        MockHttpSession owner = tenant("login-7");
        String accountId = account(owner, "Client Seven");
        MockHttpSession agent = inviteActivateAndLogin(owner, "agent@login-7.example.com", "Agent",
                UserRole.AGENT, "password123");
        String body = "{\"email\":\"person@login-7.example.com\",\"name\":\"Person\"}";

        mockMvc.perform(post("/api/v1/client-accounts/" + accountId + "/users").session(agent)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/client-accounts/" + accountId + "/users").session(tenant("login-7b"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    private MockHttpSession tenant(String suffix) throws Exception {
        return registerTenantAndGetSession("Client Co " + suffix, "Owner", "owner@" + suffix + ".example.com",
                "password123");
    }

    /** A key starts its own client account, so issuing one is how an account comes to exist. */
    private String account(MockHttpSession owner, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/api-keys").session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("key").get("clientAccountId").asText();
    }

    private String invite(MockHttpSession owner, String accountId, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/client-accounts/" + accountId + "/users").session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"Person\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CLIENT"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("inviteToken").asText();
    }

    private JsonNode list(MockHttpSession owner, String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/client-accounts/" + accountId + "/users").session(owner))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void accept(String token) throws Exception {
        mockMvc.perform(post("/api/v1/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptInviteRequest(token, "password123"))))
                .andExpect(status().isOk());
    }

    private MockHttpSession acceptAndLogin(String token, String email) throws Exception {
        accept(token);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession();
    }

    private MockHttpSession clientSession(String suffix) throws Exception {
        MockHttpSession owner = tenant(suffix);
        String email = "person@" + suffix + ".example.com";
        return acceptAndLogin(invite(owner, account(owner, "Client " + suffix), email), email);
    }
}
