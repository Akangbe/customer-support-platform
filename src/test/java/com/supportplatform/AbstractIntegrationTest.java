package com.supportplatform;

import com.supportplatform.auth.dto.AcceptInviteRequest;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.auth.dto.RegisterTenantRequest;
import com.supportplatform.customer.dto.CreateCustomerRequest;
import com.supportplatform.user.UserRole;
import com.supportplatform.user.dto.InviteUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for tests that need the full application context against a
 * real Postgres. One container is reused across every subclass (JUnit 5's
 * Testcontainers "singleton container" pattern) and Spring caches the one
 * application context across them too, since they all share this identical
 * configuration.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** Registers a fresh tenant + Owner and returns the authenticated session from that call. */
    protected MockHttpSession registerTenantAndGetSession(String tenantName, String ownerName,
                                                            String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterTenantRequest(tenantName, ownerName, email, password));

        var result = mockMvc.perform(post("/api/v1/auth/register-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession();
    }

    /** Invites, activates, and logs in a new user with the given role, returning their session. */
    protected MockHttpSession inviteActivateAndLogin(MockHttpSession inviterSession, String email, String name,
                                                       UserRole role, String password) throws Exception {
        MvcResult inviteResult = mockMvc.perform(post("/api/v1/users/invite")
                        .session(inviterSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest(email, name, role))))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(inviteResult.getResponse().getContentAsString()).get("inviteToken").asText();

        mockMvc.perform(post("/api/v1/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptInviteRequest(token, password))))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) loginResult.getRequest().getSession();
    }

    /** The authenticated user's own id, via GET /api/v1/users/me. */
    protected String extractUserId(MockHttpSession session) throws Exception {
        var result = mockMvc.perform(get("/api/v1/users/me").session(session)).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    /** Creates a customer in the caller's tenant and returns its id. */
    protected String createCustomer(MockHttpSession session, String phone, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCustomerRequest(phone, name))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
