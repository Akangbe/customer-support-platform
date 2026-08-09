package com.supportplatform;

import com.supportplatform.auth.dto.RegisterTenantRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
}
