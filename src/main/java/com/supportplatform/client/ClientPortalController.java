package com.supportplatform.client;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.client.dto.ClientMeResponse;
import com.supportplatform.client.dto.ClientMessageSearchRequest;
import com.supportplatform.client.dto.ClientPortalResponses;
import com.supportplatform.user.User;
import com.supportplatform.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
    private final ClientPortalService portalService;

    public ClientPortalController(ClientScopeResolver scopeResolver, ClientAccountRepository clientAccountRepository,
                                    UserRepository userRepository, ClientPortalService portalService) {
        this.portalService = portalService;
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

    /** Dates are whole UTC days, both inclusive, up to 92 days. Omitting them gives the last 30. */
    @GetMapping("/overview")
    public ClientPortalResponses.Overview overview(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portalService.overview(scopeResolver.resolve(principal), from, to);
    }

    @GetMapping("/stats/daily")
    public ClientPortalResponses.DailyStats dailyStats(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portalService.dailyStats(scopeResolver.resolve(principal), from, to);
    }

    @PostMapping("/messages/search")
    public ClientPortalResponses.MessagePage searchMessages(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody ClientMessageSearchRequest request) {
        return portalService.searchMessages(scopeResolver.resolve(principal), request);
    }

    @GetMapping("/messages/{messageId}")
    public ClientPortalResponses.MessageDetail message(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                         @PathVariable UUID messageId) {
        return portalService.message(scopeResolver.resolve(principal), messageId);
    }

    @GetMapping("/templates")
    public List<ClientPortalResponses.TemplateUsage> templates(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portalService.templates(scopeResolver.resolve(principal), from, to);
    }

    @GetMapping("/keys")
    public List<ClientPortalResponses.ApiKeySummary> keys(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return portalService.apiKeys(scopeResolver.resolve(principal));
    }
}
