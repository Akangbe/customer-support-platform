package com.supportplatform.auth;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.email.EmailGateway;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import com.supportplatform.user.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordResetTest extends AbstractIntegrationTest {

    private static final Pattern LINK_TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
    private static final String OLD_PASSWORD = "old-password-1";
    private static final String NEW_PASSWORD = "new-password-2";

    @MockitoBean
    private EmailGateway emailGateway;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aResetLinkSetsANewPasswordAndNotifiesTheUser() throws Exception {
        String email = owner("reset-1");

        String token = requestToken(email, 1);
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, OLD_PASSWORD).andExpect(status().isUnauthorized());
        verify(emailGateway, timeout(5000)).send(eq(email), eq("Your password was changed"), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PASSWORD_RESET_COMPLETED' "
                + "AND actor_user_id = ?", Long.class, userId(email))).isEqualTo(1);
    }

    @Test
    void aLinkWorksOnlyOnce() throws Exception {
        String email = owner("reset-2");
        String token = requestToken(email, 1);

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        confirm(token, "another-password-3").andExpect(status().isBadRequest());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aNewLinkCancelsTheEarlierOne() throws Exception {
        String email = owner("reset-3");
        String first = requestToken(email, 1);
        String second = requestToken(email, 2);

        confirm(first, NEW_PASSWORD).andExpect(status().isBadRequest());
        confirm(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void anExpiredLinkIsRefused() throws Exception {
        String email = owner("reset-4");
        String token = requestToken(email, 1);
        jdbc.update("UPDATE password_reset_token SET expires_at = now() - interval '1 second' WHERE user_id = ?",
                userId(email));

        confirm(token, NEW_PASSWORD).andExpect(status().isBadRequest());

        login(email, OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theDatabaseNeverHoldsTheToken() throws Exception {
        String email = owner("reset-5");
        String token = requestToken(email, 1);

        String stored = jdbc.queryForObject("SELECT token_hash FROM password_reset_token WHERE user_id = ?",
                String.class, userId(email));

        assertThat(stored).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void aKnownAndAnUnknownAddressGetTheSameAnswerAndOnlyOneGetsEmail() throws Exception {
        String email = owner("reset-6");

        String known = request(email).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknown = request("nobody@reset-6.example.com").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(known);
        verify(emailGateway, timeout(5000)).send(eq(email), anyString(), anyString(), anyString());
        verify(emailGateway, after(500).never())
                .send(eq("nobody@reset-6.example.com"), anyString(), anyString(), anyString());
    }

    @Test
    void anAddressIsMatchedWhateverItsCase() throws Exception {
        String email = owner("reset-7");

        request(email.toUpperCase()).andExpect(status().isAccepted());

        verify(emailGateway, timeout(5000)).send(eq(email), anyString(), anyString(), anyString());
    }

    @Test
    void requestsAreLimitedPerHour() throws Exception {
        String email = owner("reset-8");

        for (int i = 0; i < 5; i++) {
            request(email).andExpect(status().isAccepted());
        }

        verify(emailGateway, after(1500).times(3)).send(eq(email), anyString(), anyString(), anyString());
    }

    @Test
    void pendingAndDisabledUsersGetNoLink() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Reset 9", "Owner", "owner@reset-9.example.com", OLD_PASSWORD);
        String pending = "pending@reset-9.example.com";
        mockMvc.perform(post("/api/v1/users/invite").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"Pending\",\"role\":\"AGENT\"}".formatted(pending)))
                .andExpect(status().is2xxSuccessful());
        String disabled = "disabled@reset-9.example.com";
        inviteActivateAndLogin(owner, disabled, "Disabled", UserRole.AGENT, OLD_PASSWORD);
        mockMvc.perform(post("/api/v1/users/" + userId(disabled) + "/disable").session(owner))
                .andExpect(status().isOk());

        request(disabled).andExpect(status().isAccepted());
        request(pending).andExpect(status().isAccepted());

        verify(emailGateway, after(500).never())
                .send(eq(disabled), eq("Reset your password"), anyString(), anyString());
        verify(emailGateway, never()).send(eq(pending), eq("Reset your password"), anyString(), anyString());
    }

    @Test
    void aUserDisabledAfterTheLinkWasSentCannotUseIt() throws Exception {
        MockHttpSession owner = registerTenantAndGetSession("Reset 10", "Owner", "owner@reset-10.example.com", OLD_PASSWORD);
        String email = "agent@reset-10.example.com";
        inviteActivateAndLogin(owner, email, "Agent", UserRole.AGENT, OLD_PASSWORD);
        String token = requestToken(email, 1);
        mockMvc.perform(post("/api/v1/users/" + userId(email) + "/disable").session(owner))
                .andExpect(status().isOk());

        confirm(token, NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void resettingEndsEverySessionTheUserHad() throws Exception {
        String email = owner("reset-11");
        MockHttpSession existing = (MockHttpSession) login(email, OLD_PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession();
        mockMvc.perform(get("/api/v1/users/me").session(existing)).andExpect(status().isOk());

        confirm(requestToken(email, 1), NEW_PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").session(existing)).andExpect(status().isUnauthorized());
        MockHttpSession fresh = (MockHttpSession) login(email, NEW_PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession();
        mockMvc.perform(get("/api/v1/users/me").session(fresh)).andExpect(status().isOk());
    }

    @Test
    void anotherUsersSessionIsNotAffected() throws Exception {
        String email = owner("reset-12");
        String other = owner("reset-12b");
        MockHttpSession otherSession = (MockHttpSession) login(other, OLD_PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession();

        confirm(requestToken(email, 1), NEW_PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").session(otherSession)).andExpect(status().isOk());
    }

    @Test
    void confirmingDoesNotSignThePersonIn() throws Exception {
        String email = owner("reset-13");

        MockHttpSession session = (MockHttpSession) confirm(requestToken(email, 1), NEW_PASSWORD)
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession();

        mockMvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void resettingLiftsALoginLockout() throws Exception {
        String email = owner("reset-14");
        for (int i = 0; i < 5; i++) {
            loginAttemptService.recordFailure(email);
        }
        login(email, OLD_PASSWORD).andExpect(status().isTooManyRequests());

        confirm(requestToken(email, 1), NEW_PASSWORD).andExpect(status().isNoContent());

        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aBadPasswordLeavesTheLinkUsable() throws Exception {
        String email = owner("reset-15");
        String token = requestToken(email, 1);

        confirm(token, "short").andExpect(status().isBadRequest());
        confirm(token, "a".repeat(73)).andExpect(status().isBadRequest());
        confirm(token, "é".repeat(40)).andExpect(status().isBadRequest());

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void malformedRequestsAreRefused() throws Exception {
        request("not-an-email").andExpect(status().isBadRequest());
        confirm("no-such-token", NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    private String owner(String suffix) throws Exception {
        String email = "owner@" + suffix + ".example.com";
        registerTenantAndGetSession("Reset " + suffix, "Owner", email, OLD_PASSWORD);
        return email;
    }

    private java.util.UUID userId(String email) {
        return userRepository.findByEmail(email).map(User::getId).orElseThrow();
    }

    private ResultActions request(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
    }

    private ResultActions confirm(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("token", token, "password", password))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
    }

    /** Requests a link and reads the token out of the {@code expectedEmails}th email sent to this address. */
    private String requestToken(String email, int expectedEmails) throws Exception {
        request(email).andExpect(status().isAccepted());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, timeout(5000).times(expectedEmails))
                .send(eq(email), eq("Reset your password"), any(), text.capture());
        List<String> sent = text.getAllValues();
        Matcher matcher = LINK_TOKEN.matcher(sent.get(sent.size() - 1));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
