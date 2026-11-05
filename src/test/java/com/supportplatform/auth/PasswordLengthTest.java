package com.supportplatform.auth;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.email.EmailGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BCrypt reads at most 72 bytes and refuses to hash more, so a longer password must be a 400 and not a 500. */
class PasswordLengthTest extends AbstractIntegrationTest {

    private static final String TOO_LONG_ASCII = "a".repeat(73);
    /** 50 characters would pass a character limit, but it is 100 bytes. */
    private static final String TOO_LONG_MULTIBYTE = "é".repeat(50);
    private static final String LONGEST_ALLOWED = "a".repeat(72);

    @MockitoBean
    private EmailGateway emailGateway;

    @Test
    void signupRefusesPasswordsBcryptCannotHash() throws Exception {
        register("Long 1", "owner1@long.example.com", TOO_LONG_ASCII, 400);
        register("Long 2", "owner2@long.example.com", TOO_LONG_MULTIBYTE, 400);
    }

    @Test
    void signupAcceptsTheLongestPasswordBcryptCanHash() throws Exception {
        register("Long 3", "owner3@long.example.com", LONGEST_ALLOWED, 201);
    }

    @Test
    void inviteAcceptanceRefusesPasswordsBcryptCannotHash() throws Exception {
        mockMvc.perform(post("/api/v1/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", "anything", "password", TOO_LONG_MULTIBYTE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    void passwordResetRefusesPasswordsBcryptCannotHash() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", "anything", "password", TOO_LONG_MULTIBYTE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    void anOverlongLoginPasswordIsJustAWrongPassword() throws Exception {
        registerTenantAndGetSession("Long 5", "Owner", "owner5@long.example.com", "long-password-1");
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("owner5@long.example.com", "b".repeat(5000)))))
                .andExpect(status().isUnauthorized());
    }

    private void register(String tenant, String email, String password, int expected) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-tenant").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantName", tenant, "ownerName", "Owner", "email", email, "password", password))))
                .andExpect(status().is(expected));
    }
}
