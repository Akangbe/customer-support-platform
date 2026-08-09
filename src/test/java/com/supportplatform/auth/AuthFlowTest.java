package com.supportplatform.auth;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.auth.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends AbstractIntegrationTest {

    @Test
    void registeringATenantEstablishesAWorkingSession() throws Exception {
        MockHttpSession session = registerTenantAndGetSession("Flow Tenant 1", "Flow Owner 1", "flow-owner-1@example.com", "password123");

        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow-owner-1@example.com"))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void loginWithCorrectCredentialsSucceeds() throws Exception {
        registerTenantAndGetSession("Flow Tenant 2", "Flow Owner 2", "flow-owner-2@example.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("flow-owner-2@example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow-owner-2@example.com"));
    }

    @Test
    void loginWithWrongPasswordIsRejected() throws Exception {
        registerTenantAndGetSession("Flow Tenant 3", "Flow Owner 3", "flow-owner-3@example.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("flow-owner-3@example.com", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpointWithNoSessionIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = registerTenantAndGetSession("Flow Tenant 4", "Flow Owner 4", "flow-owner-4@example.com", "password123");

        mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isUnauthorized());
    }
}
