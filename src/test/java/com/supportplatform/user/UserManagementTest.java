package com.supportplatform.user;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.auth.dto.AcceptInviteRequest;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.user.dto.ChangeRoleRequest;
import com.supportplatform.user.dto.InviteUserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserManagementTest extends AbstractIntegrationTest {

    @Test
    void ownerCanInviteAndTheInviteeCanActivateAndLogIn() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Invite Co", "Invite Owner", "invite-owner-1@example.com", "password123");

        MvcResult inviteResult = mockMvc.perform(post("/api/v1/users/invite")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("new-agent@example.com", "New Agent", UserRole.AGENT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andExpect(jsonPath("$.inviteToken").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(inviteResult.getResponse().getContentAsString()).get("inviteToken").asText();

        mockMvc.perform(post("/api/v1/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptInviteRequest(token, "agent-password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new-agent@example.com"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("new-agent@example.com", "agent-password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AGENT"));
    }

    @Test
    void acceptingWithAnInvalidTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/accept-invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptInviteRequest("not-a-real-token", "password123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotInviteAnOwnerOrAdmin() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Boundary Co", "Boundary Owner", "boundary-owner-1@example.com", "password123");
        MockHttpSession admin = inviteActivateAndLogin(owner, "boundary-admin-1@example.com", "Boundary Admin", UserRole.ADMIN, "admin-password123");

        mockMvc.perform(post("/api/v1/users/invite")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("wannabe-admin@example.com", "Wannabe", UserRole.ADMIN))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/users/invite")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("wannabe-owner@example.com", "Wannabe", UserRole.OWNER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanInviteManagersAndAgents() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Boundary Co 2", "Boundary Owner 2", "boundary-owner-2@example.com", "password123");
        MockHttpSession admin = inviteActivateAndLogin(owner, "boundary-admin-2@example.com", "Boundary Admin 2", UserRole.ADMIN, "admin-password123");

        mockMvc.perform(post("/api/v1/users/invite")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("new-manager@example.com", "New Manager", UserRole.MANAGER))))
                .andExpect(status().isCreated());
    }

    @Test
    void adminCannotDisableOrChangeRoleOfTheOwner() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Boundary Co 3", "Boundary Owner 3", "boundary-owner-3@example.com", "password123");
        MockHttpSession admin = inviteActivateAndLogin(owner, "boundary-admin-3@example.com", "Boundary Admin 3", UserRole.ADMIN, "admin-password123");

        String ownerId = extractId(owner);

        mockMvc.perform(post("/api/v1/users/" + ownerId + "/disable").session(admin))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/users/" + ownerId + "/role")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeRoleRequest(UserRole.AGENT))))
                .andExpect(status().isForbidden());
    }

    @Test
    void theLastOwnerCannotBeDisabledOrDemoted() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Last Owner Co", "Last Owner", "last-owner-1@example.com", "password123");
        String ownerId = extractId(owner);

        mockMvc.perform(post("/api/v1/users/" + ownerId + "/disable").session(owner))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/v1/users/" + ownerId + "/role")
                        .session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeRoleRequest(UserRole.ADMIN))))
                .andExpect(status().isConflict());
    }

    @Test
    void aSecondOwnerCanBeDemotedOrDisabled() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Second Owner Co", "First Owner", "second-owner-1@example.com", "password123");
        MockHttpSession secondOwner = inviteActivateAndLogin(owner, "second-owner-2@example.com", "Second Owner", UserRole.OWNER, "owner2-password123");
        String secondOwnerId = extractId(secondOwner);

        mockMvc.perform(post("/api/v1/users/" + secondOwnerId + "/disable").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    private MockHttpSession inviteActivateAndLogin(MockHttpSession inviterSession, String email, String name,
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

    private String extractId(MockHttpSession session) throws Exception {
        var result = mockMvc.perform(get("/api/v1/users/me").session(session)).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
