package com.supportplatform.auth;

import com.supportplatform.auth.dto.AcceptInviteRequest;
import com.supportplatform.auth.dto.AuthResponse;
import com.supportplatform.auth.dto.LoginRequest;
import com.supportplatform.auth.dto.RegisterTenantRequest;
import com.supportplatform.user.User;
import com.supportplatform.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final TenantRegistrationService tenantRegistrationService;
    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final LoginAttemptService loginAttemptService;

    public AuthController(TenantRegistrationService tenantRegistrationService, UserService userService,
                           AuthenticationManager authenticationManager, SecurityContextRepository securityContextRepository,
                           LoginAttemptService loginAttemptService) {
        this.tenantRegistrationService = tenantRegistrationService;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/register-tenant")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse registerTenant(@Valid @RequestBody RegisterTenantRequest request,
                                        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        User owner = tenantRegistrationService.registerTenant(request.tenantName(), request.ownerName(), request.email(), request.password());
        Authentication authentication = authenticate(request.email(), request.password());
        establishSession(authentication, httpRequest, httpResponse);
        return AuthResponse.from(owner);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        loginAttemptService.checkNotLocked(request.email());
        Authentication authentication;
        try {
            authentication = authenticate(request.email(), request.password());
        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(request.email());
            throw e;
        }
        loginAttemptService.recordSuccess(request.email());

        establishSession(authentication, httpRequest, httpResponse);
        AuthenticatedPrincipal principal = (AuthenticatedPrincipal) authentication.getPrincipal();
        userService.recordLogin(principal.getTenantId(), principal.getUserId());
        return AuthResponse.from(principal);
    }

    @PostMapping("/accept-invite")
    public AuthResponse acceptInvite(@Valid @RequestBody AcceptInviteRequest request) {
        User user = userService.acceptInvite(request.token(), request.password());
        return AuthResponse.from(user);
    }

    private Authentication authenticate(String email, String password) {
        return authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
    }

    private void establishSession(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
