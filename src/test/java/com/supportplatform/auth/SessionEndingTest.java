package com.supportplatform.auth;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.email.EmailGateway;
import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionEndingTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "session-password-1";

    @MockitoBean
    private EmailGateway emailGateway;

    @Test
    void disablingAUserEndsTheirOpenSessionAtOnce() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("End 1", "Owner", "owner@end-1.example.com", PASSWORD);
        MockHttpSession agent = inviteActivateAndLogin(owner, "agent@end-1.example.com", "Agent", UserRole.AGENT, PASSWORD);
        String agentId = extractUserId(agent);
        mockMvc.perform(get("/api/v1/users/me").session(agent)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/" + agentId + "/disable").session(owner)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").session(agent)).andExpect(status().isUnauthorized());
    }

    @Test
    void enablingAgainDoesNotBringTheOldSessionBack() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("End 2", "Owner", "owner@end-2.example.com", PASSWORD);
        String email = "agent@end-2.example.com";
        MockHttpSession agent = inviteActivateAndLogin(owner, email, "Agent", UserRole.AGENT, PASSWORD);
        String agentId = extractUserId(agent);

        mockMvc.perform(post("/api/v1/users/" + agentId + "/disable").session(owner)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users/" + agentId + "/enable").session(owner)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").session(agent)).andExpect(status().isUnauthorized());
        login(email).andExpect(status().isOk());
    }

    @Test
    void changingARoleEndsTheSessionThatHeldTheOldRole() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("End 3", "Owner", "owner@end-3.example.com", PASSWORD);
        String email = "manager@end-3.example.com";
        MockHttpSession manager = inviteActivateAndLogin(owner, email, "Manager", UserRole.MANAGER, PASSWORD);
        String managerId = extractUserId(manager);

        mockMvc.perform(patch("/api/v1/users/" + managerId + "/role").session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AGENT\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").session(manager)).andExpect(status().isUnauthorized());
        login(email).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("AGENT"));
    }

    @Test
    void disablingOneUserLeavesEveryoneElseSignedIn() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("End 4", "Owner", "owner@end-4.example.com", PASSWORD);
        MockHttpSession first = inviteActivateAndLogin(owner, "first@end-4.example.com", "First", UserRole.AGENT, PASSWORD);
        MockHttpSession second = inviteActivateAndLogin(owner, "second@end-4.example.com", "Second", UserRole.AGENT, PASSWORD);

        mockMvc.perform(post("/api/v1/users/" + extractUserId(first) + "/disable").session(owner))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").session(second)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/me").session(owner)).andExpect(status().isOk());
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))));
    }
}
