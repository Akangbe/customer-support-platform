package com.supportplatform.client;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.client.dto.ClientMeResponse;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a signed-in client reads. {@code SecurityConfig} admits only the
 * CLIENT role under this path, and every handler starts from a
 * {@link ClientScope}.
 */
@RestController
@RequestMapping("/api/v1/client")
public class ClientPortalController {

    private final ClientScopeResolver scopeResolver;
    private final ClientAccountRepository clientAccountRepository;
    private final UserRepository userRepository;

    public ClientPortalController(ClientScopeResolver scopeResolver, ClientAccountRepository clientAccountRepository,
                                    UserRepository userRepository) {
        this.scopeResolver = scopeResolver;
        this.clientAccountRepository = clientAccountRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ClientMeResponse me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        ClientScope scope = scopeResolver.resolve(principal);
        ClientAccount account = clientAccountRepository.findByIdAndTenantId(scope.clientAccountId(), scope.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client account not found"));
        User user = userRepository.findByIdAndTenantId(scope.userId(), scope.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return new ClientMeResponse(user.getEmail(), user.getName(), account.getName());
    }
}
