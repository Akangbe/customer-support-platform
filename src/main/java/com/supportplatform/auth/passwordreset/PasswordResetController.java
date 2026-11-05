package com.supportplatform.auth.passwordreset;

import com.supportplatform.auth.LoginAttemptService;
import com.supportplatform.auth.SessionRevocations;
import com.supportplatform.auth.dto.FitsBcrypt;
import com.supportplatform.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {

    public record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record ConfirmRequest(@NotBlank @Size(max = 100) String token,
                                 @NotBlank @Size(min = 8, max = 100) @FitsBcrypt String password) {
    }

    private final PasswordResetRequests requests;
    private final PasswordResetService service;
    private final SessionRevocations sessionRevocations;
    private final LoginAttemptService loginAttemptService;

    public PasswordResetController(PasswordResetRequests requests, PasswordResetService service,
                                     SessionRevocations sessionRevocations, LoginAttemptService loginAttemptService) {
        this.requests = requests;
        this.service = service;
        this.sessionRevocations = sessionRevocations;
        this.loginAttemptService = loginAttemptService;
    }

    /** The same answer for every well-formed address, whether or not it has an account. */
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@Valid @RequestBody ResetRequest request) {
        requests.submit(request.email());
    }

    /**
     * Does not sign the person in: they go through the normal login, so that
     * a stolen link alone never produces a session.
     */
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody ConfirmRequest request) {
        User user = service.confirm(request.token(), request.password());
        sessionRevocations.revokeExisting(user.getId());
        loginAttemptService.recordSuccess(user.getEmail());
    }
}
