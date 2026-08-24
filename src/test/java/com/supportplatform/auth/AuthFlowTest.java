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

    @Test
    void repeatedFailedLoginsLockTheAccountOut() throws Exception {
        registerTenantAndGetSession("Flow Tenant 5", "Flow Owner 5", "flow-owner-5@example.com", "password123");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest("flow-owner-5@example.com", "wrong-password"))))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt, even with the correct password, is locked out rather than authenticated
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("flow-owner-5@example.com", "password123"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCount() throws Exception {
        registerTenantAndGetSession("Flow Tenant 6", "Flow Owner 6", "flow-owner-6@example.com", "password123");

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest("flow-owner-6@example.com", "wrong-password"))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("flow-owner-6@example.com", "password123"))))
                .andExpect(status().isOk());

        // the successful login cleared the streak, so this is only failure 1 of 5, not a lockout
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("flow-owner-6@example.com", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }
}
